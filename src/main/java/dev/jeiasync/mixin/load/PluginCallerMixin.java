package dev.jeiasync.mixin.load;

import com.google.common.base.Stopwatch;
import dev.jeiasync.AsyncConfig;
import dev.jeiasync.AsyncPluginSupport;
import mezz.jei.api.IModPlugin;
import mezz.jei.library.load.PluginCallerTimer;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/**
 * Replaces JEI's plugin registration loop with one that runs plugins off the main thread.
 * <p>
 * Every plugin is dispatched to a worker by default; nothing has to opt in. The safety net is
 * failure, not declaration: if a callback throws - anything, {@link Throwable} included - the
 * plugin is recorded as incompatible, its callback is re-run on the calling thread, and it is
 * never dispatched off-thread again for the rest of the session.
 * <p>
 * The call stays synchronous from JEI's point of view: this method does not return until every
 * plugin has finished registering. That matters because JEI reads the registration state
 * immediately after each phase, and returning early while background threads were still writing
 * would produce a half-built runtime that fails much later and further from the cause. The win is
 * that plugins register concurrently with each other instead of one after another.
 * <p>
 * The exception is {@code VanillaPlugin}, which upstream treats as a prerequisite for every other
 * plugin and whose failures are deliberately fatal; it always runs inline, first, in list order.
 * Same for anything already known to be incompatible, and for a plugin implementing
 * {@code IAsyncCompatiblePlugin} with {@code canExecuteAsync()} false.
 * <p>
 * A plugin that breaks shared state without throwing cannot be detected here. {@code
 * -Djeiasync.requireOptIn=true} restores the previous opt-in-only behaviour for that case.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.load.PluginCaller.class, remap = false)
public abstract class PluginCallerMixin {
	private static final Logger JEI_ASYNC_LOGGER = LogManager.getLogger();

	@Overwrite
	public static void callOnPlugins(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		// Only reachable if this mixin was genuinely merged into PluginCaller. See MixinApplied.
		dev.jeiasync.MixinApplied.mark("load.PluginCallerMixin");

		JEI_ASYNC_LOGGER.info("{}...", title);
		Stopwatch stopwatch = Stopwatch.createStarted();

		if (!AsyncConfig.isAsyncLoadingEnabled()) {
			JEI_ASYNC_LOGGER.info("[async-diag] {}: asyncLoading DISABLED, running {} plugins inline",
				title, plugins.size());
			callAllInline(title, plugins, func);
			JEI_ASYNC_LOGGER.info("{} took {}", title, stopwatch);
			return;
		}

		JEI_ASYNC_LOGGER.info("[async-diag] {}: asyncLoading ENABLED, {} plugins", title, plugins.size());

		List<CompletableFuture<Void>> pending = new ArrayList<>();
		List<IModPlugin> inline = new ArrayList<>();

		try (PluginCallerTimer timer = new PluginCallerTimer()) {
			for (IModPlugin plugin : plugins) {
				if (AsyncPluginSupport.isAsyncCapable(plugin)) {
					pending.add(dispatchAsync(title, plugin, func));
				} else {
					inline.add(plugin);
					runInline(plugin, func, title, timer);
				}
			}
		}

		if (!pending.isEmpty()) {
			try {
				CompletableFuture.allOf(pending.toArray(new CompletableFuture[0])).join();
			} catch (CompletionException e) {
				// Failures are already handled and retried inline per plugin.
				JEI_ASYNC_LOGGER.warn("One or more async plugins failed during {} (already handled)", title);
			}
			retryFailedInline(title, plugins, func);
		}

		JEI_ASYNC_LOGGER.info("{} took {} ({} inline, {} async)", title, stopwatch, inline.size(), pending.size());
	}

	private static CompletableFuture<Void> dispatchAsync(String title, IModPlugin plugin, Consumer<IModPlugin> func) {
		return CompletableFuture.runAsync(() -> {
			ResourceLocation pluginUid = plugin.getPluginUid();
			Stopwatch stopwatch = Stopwatch.createStarted();
			// PluginCallerTimer tracks a single open interval on the calling thread, so it is only
			// driven from the inline path.
			try {
				func.accept(plugin);
				JEI_ASYNC_LOGGER.info("Plugin {} registered during {} in {}", pluginUid, title, stopwatch);
			} catch (Throwable e) {
				// Anything at all, not just RuntimeException: plugins reach into Minecraft state and
				// the failure modes there include AssertionError and other Errors, and the point of
				// running everything off-thread is that the exception is the only signal a
				// thread-incompatible plugin gives us. If it does not throw - because it corrupted
				// shared state instead - nothing here can catch it, which is what the opt-in
				// interface and -Djeiasync.requireOptIn are for.
				AsyncPluginSupport.markIncompatible(plugin, title);
				JEI_ASYNC_LOGGER.error("Caught an error from mod plugin off-thread: {} {} (running it on the main thread instead)", plugin.getClass(), pluginUid, e);
			}
		}, AsyncPluginSupport.getExecutor());
	}

	/**
	 * Re-runs plugins whose async attempt failed, on the calling thread, so that content they
	 * failed to register is not silently lost. Each is only retried once, since a failure records
	 * the plugin as incompatible.
	 */
	private static void retryFailedInline(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		List<IModPlugin> failed = null;
		for (IModPlugin plugin : plugins) {
			if (AsyncPluginSupport.isIncompatible(plugin)) {
				if (failed == null) {
					failed = new ArrayList<>();
				}
				failed.add(plugin);
			}
		}
		if (failed == null) {
			return;
		}
		JEI_ASYNC_LOGGER.warn("Re-running {} plugin(s) on the main thread for {}...", failed.size(), title);
		for (IModPlugin plugin : failed) {
			runInline(plugin, func, title, null);
		}
	}

	private static void callAllInline(String title, List<IModPlugin> plugins, Consumer<IModPlugin> func) {
		try (PluginCallerTimer timer = new PluginCallerTimer()) {
			for (IModPlugin plugin : plugins) {
				runInline(plugin, func, title, timer);
			}
		}
	}

	private static void runInline(IModPlugin plugin, Consumer<IModPlugin> func, String title, PluginCallerTimer timer) {
		try {
			ResourceLocation pluginUid = plugin.getPluginUid();
			if (timer != null) {
				timer.begin(title, pluginUid);
			}
			func.accept(plugin);
			if (timer != null) {
				timer.end();
			}
		} catch (RuntimeException | LinkageError e) {
			if (plugin instanceof VanillaPlugin) {
				// Later plugins are going to crash if basic things added by the Vanilla Plugin
				// are missing. Better to crash immediately, so it doesn't hide the real problem.
				throw e;
			}
			JEI_ASYNC_LOGGER.error("Caught an error from mod plugin: {} {}", plugin.getClass(), plugin.getPluginUid(), e);
		}
	}
}