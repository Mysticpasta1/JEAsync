package dev.jeasync.mixin.load;

import com.google.common.base.Stopwatch;
import dev.jeasync.AsyncPluginSupport;
import dev.jeasync.FailedPlugin;
import dev.jeasync.JeiLoadProgress;
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
 * A plugin that throws off-thread is recorded as incompatible. A client-queue failure, and a
 * category icon the ingredient list rejects, are re-run once on the client thread. A duplicate id
 * or a missing class stays as the error from the attempt that threw. A concurrent modification is
 * re-run once on this thread after the other workers in the phase have finished, and the plugin
 * stays eligible for later phases when that re-run succeeds. Any other failure is re-run once on
 * this thread and stays on this thread afterwards. {@code tconstruct:jei_plugin} waits until the
 * other plugins in the phase have finished, then runs on this thread without the stuck-plugin
 * timer. Its melting-fuel scan walks every item, and its runtime callback adds and removes
 * ingredients. Avaritia and its addons do the same wait: they copy the client recipe manager, and
 * that copy has to happen after the other plugins in the phase have stopped walking it. The synced
 * list is put back immediately before they run. Vanilla's failure is still fatal on that re-run,
 * matching upstream. A plugin that
 * needs the render thread can implement
 * {@link dev.jeasync.api.IAsyncCompatiblePlugin} and return false.
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
		Stopwatch stopwatch = Stopwatch.createStarted();
		JeiLoadProgress.beginStage(title, plugins.size());

		List<CompletableFuture<Void>> pending = new ArrayList<>();
		List<IModPlugin> inline = new ArrayList<>();
		List<IModPlugin> afterWorkers = new ArrayList<>();
		List<FailedPlugin> retry = Collections.synchronizedList(new ArrayList<>());

		try (PluginCallerTimer timer = new PluginCallerTimer()) {
			int i = 0;
			while (i < plugins.size() && !AsyncPluginSupport.isAsyncCapable(plugins.get(i))) {
				IModPlugin plugin = plugins.get(i++);
				if (runsAfterWorkers(plugin)) {
					afterWorkers.add(plugin);
					continue;
				}
				inline.add(plugin);
				runInline(plugin, func, title, timer, true);
			}

			List<IModPlugin> trailingInline = new ArrayList<>();
			for (; i < plugins.size(); i++) {
				IModPlugin plugin = plugins.get(i);
				if (runsAfterWorkers(plugin)) {
					afterWorkers.add(plugin);
				} else if (AsyncPluginSupport.isAsyncCapable(plugin)) {
				pending.add(dispatchAsync(title, plugin, func, retry));
			} else {
					inline.add(plugin);
					trailingInline.add(plugin);
				}
			}
			for (IModPlugin plugin : trailingInline) {
				runInline(plugin, func, title, timer, true);
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

		for (IModPlugin plugin : afterWorkers) {
			inline.add(plugin);
			ResourceLocation pluginUid = plugin.getPluginUid();
			JEI_ASYNC_LOGGER.info("Plugin {} is running after the other plugins for {}", pluginUid, title);
			Stopwatch pluginWatch = Stopwatch.createStarted();
			if (runInline(plugin, func, title, null, true)) {
				JEI_ASYNC_LOGGER.info("Plugin {} registered during {} in {}", pluginUid, title, pluginWatch);
			}
		}

		JEI_ASYNC_LOGGER.info("{} took {} ({} inline, {} async)", title, stopwatch, inline.size(), pending.size());
	}

	private static CompletableFuture<Void> dispatchAsync(String title, IModPlugin plugin, Consumer<IModPlugin> func, List<FailedPlugin> retry) {
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
				boolean onClient = again && needsClientThread(e);
				if (again) {
					retry.add(new FailedPlugin(plugin, onClient));
				}
				String where = !again ? "" : onClient
					? " (running it on the client thread instead)"
					: " (running it on the calling thread instead)";
				JEI_ASYNC_LOGGER.error("Caught an error from mod plugin off-thread: {} {}{}", plugin.getClass(), pluginUid, where, e);
			} finally {
				JeiLoadProgress.pluginFinished();
			}
		}), AsyncPluginSupport.getExecutor());
	}

	/**
	 * Re-runs plugins whose async attempt failed in this phase. Progress was already counted.
	 * Plugins demoted by an earlier phase already ran on this thread above, so they are not in
	 * {@code retry}.
	 */
	private static void retryFailed(String title, List<FailedPlugin> retry, Consumer<IModPlugin> func) {
		if (retry.isEmpty()) {
			return;
		}
		List<FailedPlugin> failed = new ArrayList<>(retry);
		int onClient = 0;
		int onCaller = 0;
		for (FailedPlugin plugin : failed) {
			if (plugin.onClient) {
				onClient++;
			} else {
				onCaller++;
			}
		}
		if (onClient > 0) {
			JEI_ASYNC_LOGGER.warn("Re-running {} plugin(s) on the client thread for {}...", onClient, title);
		}
		if (onCaller > 0) {
			JEI_ASYNC_LOGGER.warn("Re-running {} plugin(s) on the calling thread for {}...", onCaller, title);
		}
		for (FailedPlugin plugin : failed) {
			try {
				boolean ok;
				if (plugin.onClient) {
					WorkerEnvironment.runOnClient(() -> runInline(plugin.plugin, func, title, null, false));
					ok = true;
				} else {
					ok = runInline(plugin.plugin, func, title, null, false);
				}
				if (!ok && !AsyncPluginSupport.isIncompatible(plugin.plugin)) {
					AsyncPluginSupport.markIncompatible(plugin.plugin, title);
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
	 * Tinkers' Construct iterates every item while other plugins remove ingredients, then adds
	 * potion fluids at runtime. Avaritia and its addons copy the client recipe manager. Both run
	 * after the workers for the phase have joined, on this thread, and without the stuck-plugin
	 * timer.
	 */
	private static boolean runsAfterWorkers(IModPlugin plugin) {
		ResourceLocation uid = plugin.getPluginUid();
		if (uid != null && "tconstruct".equals(uid.getNamespace()) && "jei_plugin".equals(uid.getPath())) {
			return true;
		}
		return readsClientRecipes(plugin);
	}

	/**
	 * Re:Avaritia's plugin uid is {@code avaritia:jei_plugin}. Addons use their own namespace or
	 * class, and the recipes they add are datapack recipes in the same manager. Matching the name
	 * covers both without putting those mods on the compile classpath.
	 */
	private static boolean readsClientRecipes(IModPlugin plugin) {
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

	private static boolean needsClientThread(Throwable error) {
		Throwable current = error;
		while (current != null) {
			if (current instanceof RenderThreadGuard.OffThreadClientExecutorException) {
				return true;
			}
			if (current instanceof IllegalArgumentException) {
				String message = current.getMessage();
				if (message != null && message.contains("cannot be used as a drawable ingredient")) {
					return true;
				}
			}
			current = current.getCause();
		}
		return false;
	}

	private static boolean runInline(IModPlugin plugin, Consumer<IModPlugin> func, String title, PluginCallerTimer timer, boolean trackProgress) {
		try {
			ResourceLocation pluginUid = plugin.getPluginUid();
			if (timer != null) {
				timer.begin(title, pluginUid);
			}
			if (readsClientRecipes(plugin)) {
				restoreClientRecipes();
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
