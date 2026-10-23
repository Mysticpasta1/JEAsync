package dev.jeasync;

import mezz.jei.api.IModPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * Worker pool, and the plugins that have already failed off the calling thread.
 * <p>
 * Every plugin is dispatched, vanilla included. One can stay on the calling thread by implementing
 * {@link dev.jeasync.api.IAsyncCompatiblePlugin} and returning false from {@code canExecuteAsync()}.
 * A plugin that throws off-thread is recorded here and kept on the calling thread for the rest
 * of the session. The caller decides which of those failures are safe to run a second time.
 */
public final class AsyncPluginSupport {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final Set<IModPlugin> INCOMPATIBLE =
            Collections.newSetFromMap(Collections.synchronizedMap(new IdentityHashMap<>()));

    private static volatile ExecutorService executor;
    private static volatile ExecutorService startupExecutor;

    private AsyncPluginSupport() {
    }

    /**
     * Workers must use the game classloader, not the common pool's application loader.
     * <p>
     * Forge's event transformer and {@code ServiceLoader.load(Class)} both read the context
     * classloader. The common pool's loader cannot see Minecraft or mods, so a plugin that
     * transforms an event or loads a service off-thread either drops its handlers or poisons the
     * class for the rest of the session. {@link WorkerEnvironment} installs the loader that defined
     * Minecraft, at thread creation and again around every task.
     */
    private static ThreadFactory workerFactory(String name) {
        return r -> {
            Thread thread = new Thread(WorkerEnvironment.workerThreadGroup(), r, name);
            // Daemon so a wedged plugin can never hold the JVM open.
            thread.setDaemon(true);
            ClassLoader loader = WorkerEnvironment.gameClassLoader();
            if (loader != null) {
                thread.setContextClassLoader(loader);
            }
            return thread;
        };
    }

    /**
     * Installs the game classloader around every task. Registration callbacks additionally install
     * the pulled client handles themselves; search tasks must not, because those handles are a
     * startup snapshot.
     */
    private static final class ContextExecutor extends ThreadPoolExecutor {
        private ContextExecutor(int threads, ThreadFactory factory) {
            super(threads, threads, 30L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), factory);
        }

        @Override
        public void execute(Runnable command) {
            super.execute(WorkerEnvironment.wrapLoader(command));
        }
    }

    public static ExecutorService getExecutor() {
        ExecutorService current = executor;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        synchronized (AsyncPluginSupport.class) {
            current = executor;
            if (current == null || current.isShutdown()) {
                WorkerEnvironment.capture();
                ContextExecutor pool = new ContextExecutor(
                        AsyncConfig.getThreadCount(),
                        workerFactory("jeasync Plugin Loader"));
                // Let the pool shrink back to zero once loading is done.
                pool.allowCoreThreadTimeOut(true);
                executor = pool;
                current = pool;
            }
            return current;
        }
    }

    /**
     * A single-threaded executor for JEI's own startup, separate from the plugin pool.
     * <p>
     * Single-threaded on purpose: {@code startRunnable} is one sequential unit of work, and running
     * any part of it in parallel would be the same hazard as dispatching it at all.
     */
    public static ExecutorService getStartupExecutor() {
        ExecutorService current = startupExecutor;
        if (current != null && !current.isShutdown()) {
            return current;
        }
        synchronized (AsyncPluginSupport.class) {
            current = startupExecutor;
            if (current == null || current.isShutdown()) {
                WorkerEnvironment.capture();
                ContextExecutor startup = new ContextExecutor(1, workerFactory("jeasync Startup"));
                startup.allowCoreThreadTimeOut(true);
                startupExecutor = startup;
                current = startup;
            }
            return current;
        }
    }

    /**
     * Whether this plugin's registration callback may run on a worker.
     * <p>
     * A plugin already recorded as incompatible stays on the calling thread. That check comes
     * first so a plugin that failed once is not dispatched again. Vanilla is included: upstream
     * still treats a vanilla failure as fatal, which {@code PluginCaller} preserves on the retry.
     */
    public static boolean isAsyncCapable(IModPlugin plugin) {
        if (isIncompatible(plugin)) {
            return false;
        }
        if (plugin instanceof dev.jeasync.api.IAsyncCompatiblePlugin asyncPlugin) {
            return asyncPlugin.canExecuteAsync();
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
        ExecutorService currentStartup;
        synchronized (AsyncPluginSupport.class) {
            current = executor;
            executor = null;
            currentStartup = startupExecutor;
            startupExecutor = null;
        }
        INCOMPATIBLE.clear();
        shutdown(current, "plugin loader");
        shutdown(currentStartup, "startup");
    }

    private static void shutdown(ExecutorService service, String what) {
        if (service == null || service.isShutdown()) {
            return;
        }
        service.shutdownNow();
        try {
            if (!service.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.warn("Async {} did not terminate in time", what);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}