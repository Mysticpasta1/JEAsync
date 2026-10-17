package dev.jeiasync;

import mezz.jei.api.IModPlugin;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Shared state for async plugin registration: the worker pool, and the set of plugins that have
 * been caught violating thread-safety.
 * <p>
 * Every plugin is eligible by default. A plugin is recorded as incompatible the first time one of
 * its async callbacks throws, and is never dispatched off-thread again for the rest of the
 * session. That is deliberately sticky within a run: a plugin that fails off-thread once is very
 * likely to fail again, and retrying it every load phase would pay the cost repeatedly.
 */
public final class AsyncPluginSupport {
	private static final Logger LOGGER = LogManager.getLogger();

	private static final Set<IModPlugin> INCOMPATIBLE =
		Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));

	private static volatile ExecutorService executor;

	private AsyncPluginSupport() {
	}

	public static ExecutorService getExecutor() {
		ExecutorService current = executor;
		if (current != null && !current.isShutdown()) {
			return current;
		}
		synchronized (AsyncPluginSupport.class) {
			current = executor;
			if (current == null || current.isShutdown()) {
				ThreadPoolExecutor pool = new ThreadPoolExecutor(
					AsyncConfig.getThreadCount(), AsyncConfig.getThreadCount(),
					30L, TimeUnit.SECONDS,
					new LinkedBlockingQueue<>(),
					r -> {
						Thread thread = new Thread(r, "JEI Async Plugin Loader");
						// Daemon so a wedged plugin can never hold the JVM open.
						thread.setDaemon(true);
						return thread;
					}
				);
				// Let the pool shrink back to zero once loading is done.
				pool.allowCoreThreadTimeOut(true);
				executor = pool;
				current = pool;
			}
			return current;
		}
	}

	/**
	 * Whether this plugin's registration callback may be dispatched to a worker.
	 * <p>
	 * By default, yes - every plugin is eligible, and one that cannot cope is demoted to the
	 * calling thread the moment it throws. The exceptions:
	 * <ul>
	 *     <li>{@code VanillaPlugin}, which upstream treats as a prerequisite for everything else
	 *     and whose failures are meant to be fatal</li>
	 *     <li>plugins already recorded as incompatible by an earlier phase</li>
	 *     <li>plugins implementing {@code IAsyncCompatiblePlugin} whose
	 *     {@code canExecuteAsync()} returns false, which is a way for an author who knows their
	 *     plugin is main-thread-only to say so without this add-on having to discover it</li>
	 * </ul>
	 */
	public static boolean isAsyncCapable(IModPlugin plugin) {
		if (plugin instanceof VanillaPlugin) {
			return false;
		}
		if (isIncompatible(plugin)) {
			return false;
		}
		if (AsyncConfig.isOptInOnly()) {
			return plugin instanceof dev.jeiasync.api.IAsyncCompatiblePlugin;
		}
		if (plugin instanceof dev.jeiasync.api.IAsyncCompatiblePlugin) {
			return ((dev.jeiasync.api.IAsyncCompatiblePlugin) plugin).canExecuteAsync();
		}
		return true;
	}

	public static boolean isIncompatible(IModPlugin plugin) {
		return INCOMPATIBLE.contains(plugin);
	}

	/**
	 * Records that a plugin failed when run off-thread, so it stops being dispatched there.
	 */
	public static void markIncompatible(IModPlugin plugin, String phase) {
		if (INCOMPATIBLE.add(plugin)) {
			LOGGER.warn("Plugin {} failed off the main thread during {} ({}); it will run inline from now on.",
				plugin.getPluginUid(), phase, plugin.getClass().getName());
		}
	}

	public static void clearIncompatible() {
		INCOMPATIBLE.clear();
	}

	public static void shutdown() {
		ExecutorService current;
		synchronized (AsyncPluginSupport.class) {
			current = executor;
			executor = null;
		}
		INCOMPATIBLE.clear();
		if (current != null && !current.isShutdown()) {
			current.shutdownNow();
			try {
				if (!current.awaitTermination(5, TimeUnit.SECONDS)) {
					LOGGER.warn("Async plugin loader did not terminate in time");
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}