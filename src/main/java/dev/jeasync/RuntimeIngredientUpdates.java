package dev.jeasync;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;

/**
 * Serializes search-index updates for ingredients added while plugins are still running.
 * <p>
 * Sending Runtime adds and removes on many plugin threads at once. Building a list element reads
 * tooltips, and inserting those strings into the live suffix tree is not safe from two threads.
 * The plugin thread only enqueues the batch. One update runs at a time, on the shared worker
 * pool, so the phase can finish without waiting for that work.
 */
public final class RuntimeIngredientUpdates {
	/**
	 * Monitor for the mutable suffix tree. Searches take it while they read the tree, and runtime
	 * inserts take it while they write. The baked index is not touched.
	 */
	public static final Object SEARCH_LOCK = new Object();

	private static final Logger LOGGER = LogManager.getLogger();

	private final Object chainLock = new Object();
	private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);

	/**
	 * Runs {@code task} after every update already queued. A failure in an earlier update does
	 * not drop this one.
	 */
	public void submit(Runnable task) {
		synchronized (chainLock) {
			tail = tail.handle((ignored, error) -> null).thenRunAsync(() -> {
				try {
					task.run();
				} catch (Throwable thrown) {
					LOGGER.error("jeasync: runtime ingredient search update failed", thrown);
				}
			}, AsyncPluginSupport.getExecutor());
		}
	}
}
