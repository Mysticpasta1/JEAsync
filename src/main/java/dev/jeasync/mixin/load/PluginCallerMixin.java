package dev.jeasync.mixin.load;

import com.google.common.base.Stopwatch;
import dev.jeasync.AsyncPluginSupport;
import dev.jeasync.compat.RecipeViewerCatalogCache;
import dev.jeasync.JeiLoadProgress;
import dev.jeasync.RegistryFreeze;
import dev.jeasync.RenderThreadGuard;
import dev.jeasync.WorkerEnvironment;
import mezz.jei.api.IModPlugin;
import mezz.jei.library.load.PluginCallerTimer;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Locale;
import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Runs each plugin phase across the worker pool.
 * <p>
 * The phase itself still finishes before JEI continues: the next phase reads what this one
 * registered. That wait is on the startup thread. The render thread is not in it, so a container
 * opened mid-load is handled by {@link dev.jeasync.mixin.startup.StartEventObserverMixin} and
 * {@link dev.jeasync.client.JeiLoadOverlay}.
 * <p>
 * This stays a head injection, at low priority, so other add-ons that also inject here still run.
 * Cancelling skips JEI's own loop. Overwriting the method would delete their injectors.
 * <p>
 * A plugin that throws off-thread is recorded as incompatible. A duplicate id or a missing class
 * stays as the error from the attempt that threw. A concurrent modification is re-run once after
 * the other workers in the phase have finished, and the plugin stays eligible for later phases
 * when that re-run succeeds. Any other failure is re-run once, and a demoted plugin is re-run for
 * every later phase, on the client thread: the startup thread is still off the render thread, so
 * a plugin that needed the client thread used to fail there a second time. The retries happen
 * after the phase's workers have joined and are waited on by the startup thread, which is safe
 * because the render thread is back in its loop while startup owns the load.
 * <p>
 * Anything that cannot run on a worker is held until that phase's workers have joined, then run
 * on the client thread, in the order the plugins were listed. That is a plugin that opted out of
 * async, a plugin already demoted, and a plugin that has to see the other plugins' results.
 * {@code tconstruct:jei_plugin} is the last of those: its melting-fuel scan walks every item, and
 * its runtime callback adds and removes ingredients, so it runs without the stuck-plugin timer.
 * Avaritia and its addons do the same wait: they copy the client recipe manager, and that copy
 * has to happen after the other plugins in the phase have stopped walking it. Vanilla's failure is
 * still fatal on that re-run, matching upstream. A dedicated server has no client thread, so the
 * same inline work runs on the caller.
 * <p>
 * The synced recipe list is put back on the client manager once at the head of a phase, before any
 * plugin is handed to a worker or run inline. Plugins that copy the manager during registration
 * read that list, and the addons among them do not name themselves after the mod they extend, so
 * no plugin's name is a usable test for whether it needs the restore. It costs nothing when the
 * manager already holds the sync, so every phase pays it.
 * <p>
 * {@code Sending ConfigManager} runs from {@code JeiStarter}'s constructor, on a mod-loading
 * worker, while other workers are still registering blocks and items. Fanning that phase out
 * reads {@code ForgeRegistry}'s maps during those writes, and the maps drop entries that were
 * already stored, vanilla and modded. Any phase that arrives before the item registry is frozen
 * stays on the calling thread. Later phases still use the pool.
 * <p>
 * That phase hands out the adapter over the config library's schemas, and none of them name a
 * file until a world exists for them to resolve against, so the adapter throws for an inactive
 * schema until then. A config reader outside JEI takes that as a crash, which
 * {@link dev.jeasync.mixin.compat.ConfigManagerAdapterMixin} answers by naming the schema's own
 * file. Distributing the adapter is not where that state is decided, so the phase's threading
 * stays as above.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.load.PluginCaller.class, remap = false, priority = 0)
public abstract class PluginCallerMixin {
	private static final Logger JEI_ASYNC_LOGGER = LogManager.getLogger();
	private static volatile Method jeiAsyncRestoreRecipes;

	@Inject(method = "callOnPlugins", at = @At("HEAD"), cancellable = true, remap = false)
	private static void jeiAsyncCallOnPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func, CallbackInfo ci) {
		dev.jeasync.MixinApplied.mark("load.PluginCallerMixin");
		ci.cancel();
		callOnPluginsAsync(title, plugins, func);
	}

	private static void callOnPluginsAsync(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		// Before the registry freezes, stay on this thread and do not install client handles or
		// pull services. Both of those reach into registries that other mod-loading workers are
		// still filling.
		if (!RegistryFreeze.itemsLocked()) {
			dispatchWhileRegistriesOpen(title, plugins, func);
			return;
		}
		boolean onClient = WorkerEnvironment.isClientGameThread();
		if (onClient) {
			RenderThreadGuard.enterClientDispatch();
		}
		try {
			WorkerEnvironment.callWithLoader(() -> dispatchPlugins(title, plugins, func));
		} finally {
			if (onClient) {
				RenderThreadGuard.exitClientDispatch();
			}
		}
	}

	private static void dispatchPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		JEI_ASYNC_LOGGER.info("{}...", title);
		// Before the first worker reads the client recipe manager, put the server's list back on it.
		// The client thread owns that manager, and it is free here: startup holds the load, not the loop.
		WorkerEnvironment.runOnClient(PluginCallerMixin::restoreClientRecipes);
		// A plugin that caches a built catalog on itself keeps it for the session. Remembering the
		// instance costs nothing and lets the load boundary hand that heap back.
		RecipeViewerCatalogCache.remember(plugins);
		Stopwatch stopwatch = Stopwatch.createStarted();
		JeiLoadProgress.beginStage(title, plugins.size());

		List<CompletableFuture<Void>> pending = new ArrayList<>();
		List<IModPlugin> inline = new ArrayList<>();
		List<IModPlugin> retry = Collections.synchronizedList(new ArrayList<>());

		for (IModPlugin plugin : plugins) {
			if (needsInline(plugin)) {
				inline.add(plugin);
			} else {
				pending.add(dispatchAsync(title, plugin, func, retry));
			}
		}

		if (!pending.isEmpty()) {
			try {
				CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();
			} catch (CompletionException e) {
				JEI_ASYNC_LOGGER.warn("One or more async plugins failed during {} (already handled)", title);
			}
			retryFailed(title, retry, func);
		}

		// Inline work waits until the workers for this phase have joined, then runs on the client
		// thread. The startup thread is not the render thread.
		try (PluginCallerTimer timer = new PluginCallerTimer()) {
			for (IModPlugin plugin : inline) {
				boolean after = runsAfterWorkers(plugin);
				ResourceLocation pluginUid = plugin.getPluginUid();
				Stopwatch pluginWatch = after ? Stopwatch.createStarted() : null;
				if (after) {
					JEI_ASYNC_LOGGER.info("Plugin {} is running after the other plugins for {}", pluginUid, title);
				}
				boolean ok = runOnClientThread(plugin, func, title, after ? null : timer, true);
				if (after && ok) {
					JEI_ASYNC_LOGGER.info("Plugin {} registered during {} in {}", pluginUid, title, pluginWatch);
				}
			}
		}

		// Summarise any plugins that failed during this phase so the failure is not
		// buried among tens of thousands of log lines.
		List<String> failedPlugins = new ArrayList<>();
		for (IModPlugin p : plugins) {
			if (AsyncPluginSupport.isIncompatible(p)) {
				failedPlugins.add(String.valueOf(p.getPluginUid()));
			}
		}
		if (!failedPlugins.isEmpty()) {
			JEI_ASYNC_LOGGER.error("jeasync: {} plugin(s) failed during {} and were skipped: {}", failedPlugins.size(), title, failedPlugins);
		}
		JEI_ASYNC_LOGGER.info("{} took {} ({} inline, {} async)", title, stopwatch, inline.size(), pending.size());
	}

	/**
	 * Runs one phase on the thread that called it.
	 * <p>
	 * The item registry is still accepting registrations, so a worker must not touch it. Upstream
	 * runs this phase on the mod-loading thread that constructed JEI. This does the same.
	 */
	private static void dispatchWhileRegistriesOpen(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		JEI_ASYNC_LOGGER.info("{}...", title);
		// This phase runs while the client thread is blocked joining the mod-loading worker, so the
		// restore happens here on the calling thread and is not scheduled onto the client.
		restoreClientRecipes();
		RecipeViewerCatalogCache.remember(plugins);
		JEI_ASYNC_LOGGER.info("jeasync: {} is staying on the calling thread because the item registry is still open", title);
		Stopwatch stopwatch = Stopwatch.createStarted();
		JeiLoadProgress.beginStage(title, plugins.size());
		try (PluginCallerTimer timer = new PluginCallerTimer()) {
			for (IModPlugin plugin : plugins) {
				runInline(plugin, func, title, timer, true);
			}
		}
		JEI_ASYNC_LOGGER.info("{} took {} ({} inline, 0 async)", title, stopwatch, plugins.size());
	}

	private static CompletableFuture<Void> dispatchAsync(String title, IModPlugin plugin, Consumer<IModPlugin> func, List<IModPlugin> retry) {
		return CompletableFuture.runAsync(() -> WorkerEnvironment.runRegistration(() -> {
			ResourceLocation pluginUid = plugin.getPluginUid();
			Stopwatch stopwatch = Stopwatch.createStarted();
			try {
				RenderThreadGuard.enterPluginRegistration();
				try {
					func.accept(plugin);
				} finally {
					RenderThreadGuard.exitPluginRegistration();
				}
				JEI_ASYNC_LOGGER.info("Plugin {} registered during {} in {}", pluginUid, title, stopwatch);
			} catch (Throwable e) {
				boolean again = shouldRetry(plugin, e);
				boolean raced = again && isConcurrentModification(e);
				// A race is retried after this phase joins. Demote the plugin only when that retry fails.
				if (!raced) {
					AsyncPluginSupport.markIncompatible(plugin, title);
				}
				if (again) {
					retry.add(plugin);
				}
				String where = again ? " (running it on " + retryThread() + " instead)" : "";
				JEI_ASYNC_LOGGER.error("Caught an error from mod plugin off-thread: {} {}{}", plugin.getClass(), pluginUid, where, e);
			} finally {
				JeiLoadProgress.pluginFinished();
			}
		}), AsyncPluginSupport.getExecutor());
	}

	/**
	 * Re-runs plugins whose async attempt failed in this phase, once the workers have joined, on
	 * the client thread. Progress was already counted. Plugins demoted by an earlier phase are
	 * in the inline list and run on the client thread after this, so they are not in {@code retry}.
	 */
	private static void retryFailed(String title, List<IModPlugin> retry, Consumer<IModPlugin> func) {
		if (retry.isEmpty()) {
			return;
		}
		List<IModPlugin> failed = new ArrayList<>(retry);
		JEI_ASYNC_LOGGER.warn("Re-running {} plugin(s) on {} for {}...", failed.size(), retryThread(), title);
		for (IModPlugin plugin : failed) {
			try {
				boolean ok = runOnClientThread(plugin, func, title, null, false);
				if (!ok && !AsyncPluginSupport.isIncompatible(plugin)) {
					AsyncPluginSupport.markIncompatible(plugin, title);
				}
			} catch (CompletionException e) {
				Throwable cause = e.getCause();
				if (cause instanceof RuntimeException runtime) {
					throw runtime;
				}
				if (cause instanceof Error error) {
					throw error;
				}
				throw e;
			}
		}
	}

	/**
	 * A second pass repeats whatever the first pass already published. That is safe for a plugin
	 * that failed before it registered anything. It is not safe once an id is taken, or when the
	 * class the plugin needs is absent. A concurrent modification is retried here because the
	 * other workers have already joined, so the collection they were editing is stable. An index
	 * that was only half written still is not. Vanilla stays fatal, so it is always re-run.
	 */
	private static boolean shouldRetry(IModPlugin plugin, Throwable error) {
		if (plugin instanceof VanillaPlugin) {
			return true;
		}
		Throwable current = error;
		while (current != null) {
			if (current instanceof ClassNotFoundException || current instanceof NoClassDefFoundError) {
				return false;
			}
			if (current instanceof ArrayIndexOutOfBoundsException) {
				return false;
			}
			if (current instanceof IllegalArgumentException) {
				String message = current.getMessage();
				if (message != null && message.toLowerCase(Locale.ROOT).contains("already")) {
					return false;
				}
			}
			current = current.getCause();
		}
		return true;
	}

	/**
	 * A plugin stays off the worker pool when it opted out, when an earlier phase demoted it, or
	 * when it has to run after the other plugins in the phase. Those run on the client thread
	 * after the workers join.
	 */
	private static boolean needsInline(IModPlugin plugin) {
		return runsAfterWorkers(plugin) || !AsyncPluginSupport.isAsyncCapable(plugin);
	}

	/**
	 * Tinkers' Construct iterates every item while other plugins remove ingredients, then adds
	 * potion fluids at runtime. Avaritia and its addons copy the client recipe manager. Both run
	 * after the workers for the phase have joined, on the client thread, and without the
	 * stuck-plugin timer.
	 */
	private static boolean runsAfterWorkers(IModPlugin plugin) {
		ResourceLocation uid = plugin.getPluginUid();
		if (uid != null && "tconstruct".equals(uid.getNamespace()) && "jei_plugin".equals(uid.getPath())) {
			return true;
		}
		return copiesClientRecipes(plugin);
	}

	/**
	 * Re:Avaritia's plugin uid is {@code avaritia:jei_plugin}. Addons use their own namespace or
	 * class, and the recipes they add are datapack recipes in the same manager. Matching the name
	 * covers both without putting those mods on the compile classpath.
	 * <p>
	 * The name is only a guess at who copies the manager, so it is used for ordering alone, where
	 * over-applying it is harmless: a plugin run after the workers have joined is simply a plugin
	 * run later. Which plugins get the synced list cannot be guessed this way, so that no longer
	 * depends on the name.
	 */
	private static boolean copiesClientRecipes(IModPlugin plugin) {
		ResourceLocation uid = plugin.getPluginUid();
		if (uid != null) {
			String namespace = uid.getNamespace();
			if (namespace != null && namespace.toLowerCase(Locale.ROOT).contains("avaritia")) {
				return true;
			}
			String path = uid.getPath();
			if (path != null && path.toLowerCase(Locale.ROOT).contains("avaritia")) {
				return true;
			}
		}
		return plugin.getClass().getName().toLowerCase(Locale.ROOT).contains("avaritia");
	}

	private static void restoreClientRecipes() {
		if (FMLEnvironment.dist.isDedicatedServer()) {
			return;
		}
		try {
			Method method = jeiAsyncRestoreRecipes;
			if (method == null) {
				method = Class.forName("dev.jeasync.ClientRecipes").getMethod("restoreIfDrifted");
				jeiAsyncRestoreRecipes = method;
			}
			method.invoke(null);
		} catch (ReflectiveOperationException failure) {
			JEI_ASYNC_LOGGER.error("jeasync: could not restore synced recipes", failure);
		}
	}

	private static boolean isConcurrentModification(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof ConcurrentModificationException) {
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	private static String retryThread() {
		return FMLEnvironment.dist.isDedicatedServer() ? "the calling thread" : "the client thread";
	}

	/**
	 * Runs a plugin callback on the client thread when startup owns the caller.
	 * <p>
	 * This is the thread JEI itself would have used. A plugin that failed off-thread used to be
	 * re-run on the startup worker, which is still off the render thread; a plugin that needed the
	 * client thread failed there a second time. A call already on the client thread, and a
	 * dedicated server, run directly. Waiting here is safe: the render thread is back in its loop
	 * while startup owns the load, and the phase's workers have already been joined. Inline plugins
	 * use this only after that join.
	 */
	private static boolean runOnClientThread(IModPlugin plugin, Consumer<IModPlugin> func, String title, PluginCallerTimer timer, boolean trackProgress) {
		boolean[] ran = {false};
		WorkerEnvironment.runOnClient(() -> ran[0] = runInline(plugin, func, title, timer, trackProgress));
		return ran[0];
	}

	private static boolean runInline(IModPlugin plugin, Consumer<IModPlugin> func, String title, PluginCallerTimer timer, boolean trackProgress) {
		try {
			ResourceLocation pluginUid = plugin.getPluginUid();
			if (timer != null) {
				timer.begin(title, pluginUid);
			}
			func.accept(plugin);
			if (timer != null) {
				timer.end();
			}
			return true;
		} catch (RuntimeException | LinkageError e) {
			if (plugin instanceof VanillaPlugin) {
				throw e;
			}
			JEI_ASYNC_LOGGER.error("Caught an error from mod plugin: {} {}", plugin.getClass(), plugin.getPluginUid(), e);
			return false;
		} finally {
			if (trackProgress) {
				JeiLoadProgress.pluginFinished();
			}
		}
	}
}
