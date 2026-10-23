package dev.jeasync;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Runs ingredient list filtering off the render thread.
 * <p>
 * Filtering is a sort plus a per-element visibility check across the whole ingredient list, which
 * is far too much to do while a frame is being drawn. The work is handed to the shared worker pool
 * and the previously computed list keeps being returned until a newer one lands, so typing in the
 * search box never blanks the ingredient list out.
 * <p>
 * Staleness is handled with a generation counter rather than by cancelling: each submission takes
 * the next generation, and the caller decides whether a finished list is still worth publishing.
 * Cancelling mid-sort is not possible anyway, since the running code has no cancellation points.
 * <p>
 * The same query is not started again while it is in flight or after it has finished, until
 * {@link #invalidate()} marks the ingredient set stale. A newer query that arrives while a sort is
 * running replaces that sort once, instead of stacking one sort per call.
 * <p>
 * List filtering has its own generation. An ingredient-set change does not drop a search-index
 * rebuild that is still publishing.
 * <p>
 * One coordinator exists per {@code IngredientFilter}. {@link #submit} and {@link #invalidate} may
 * be called from the render thread and from workers.
 */
public final class AsyncSearchCoordinator {
	private final Object gate = new Object();
	/** Search-index rebuilds only. List filtering uses {@link #listGeneration}. */
	private final AtomicLong generation = new AtomicLong();
	private final AtomicLong listGeneration = new AtomicLong();
	private volatile String submittedFilter = "";
	private volatile boolean stale = true;
	private volatile CompletableFuture<?> pending;
	private boolean running;
	private boolean completed;
	private boolean followUp;
	private String followFilter;
	private Supplier<?> followTask;
	private BiConsumer<Long, Object> followDone;

	/**
	 * Submits {@code task} unless the same filter text is already in flight or already finished,
	 * and runs {@code onDone} on the worker with the generation taken for this attempt.
	 * <p>
	 * {@code onDone} is invoked even when a newer attempt has been queued. Use
	 * {@link #isCurrent(long)} before replacing a list the player can already see.
	 */
	public void submit(String filterText, Supplier<?> task, BiConsumer<Long, Object> onDone) {
		if (filterText == null) {
			filterText = "";
		}
		synchronized (gate) {
			boolean same = filterText.equals(this.submittedFilter);
			if (same && !this.stale && (this.running || this.completed)) {
				return;
			}
			if (this.running) {
				this.stale = true;
				this.followUp = true;
				this.followFilter = filterText;
				this.followTask = task;
				this.followDone = onDone;
				return;
			}
			launch(filterText, task, onDone);
		}
	}

	private void launch(String filterText, Supplier<?> task, BiConsumer<Long, Object> onDone) {
		this.stale = false;
		this.completed = false;
		this.followUp = false;
		this.followTask = null;
		this.followDone = null;
		this.submittedFilter = filterText;
		this.running = true;
		long myGeneration = this.listGeneration.incrementAndGet();

		ExecutorService executor = AsyncPluginSupport.getExecutor();
		CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
			Throwable error = null;
			try {
				Object result = task.get();
				onDone.accept(myGeneration, result);
				return result;
			} catch (Throwable thrown) {
				error = thrown;
				if (thrown instanceof RuntimeException runtime) {
					throw runtime;
				}
				if (thrown instanceof Error failure) {
					throw failure;
				}
				throw new IllegalStateException(thrown);
			} finally {
				finish(error);
			}
		}, executor);

		this.pending = future;
	}

	private void finish(Throwable error) {
		Supplier<?> nextTask = null;
		BiConsumer<Long, Object> nextDone = null;
		String nextFilter = null;
		synchronized (gate) {
			this.running = false;
			if (error != null) {
				this.stale = true;
				this.completed = false;
				org.apache.logging.log4j.LogManager.getLogger(AsyncSearchCoordinator.class)
					.warn("jeasync: ingredient list filter failed", error);
			} else if (!this.stale) {
				this.completed = true;
			}
			if (this.followUp && this.followTask != null && this.followDone != null && this.followFilter != null) {
				nextTask = this.followTask;
				nextDone = this.followDone;
				nextFilter = this.followFilter;
				this.followUp = false;
				this.followTask = null;
				this.followDone = null;
			}
		}
		if (nextTask != null && nextDone != null && nextFilter != null) {
			submit(nextFilter, nextTask, nextDone);
		}
	}

	public boolean isPending() {
		synchronized (gate) {
			if (this.running) {
				return true;
			}
		}
		CompletableFuture<?> current = this.pending;
		return current != null && !current.isDone();
	}

	/** True if no list submission has been made since {@code myGeneration} was taken. */
	public boolean isCurrent(long myGeneration) {
		return listGeneration.get() == myGeneration;
	}

	/**
	 * Forces the next {@link #submit} to actually run, even if the filter text is unchanged.
	 * Used when the underlying ingredient set changed rather than the query.
	 */
	public void invalidate() {
		synchronized (gate) {
			this.stale = true;
			this.completed = false;
			this.listGeneration.incrementAndGet();
		}
	}

	public void reset() {
		synchronized (gate) {
			this.stale = true;
			this.completed = false;
			this.running = false;
			this.followUp = false;
			this.followTask = null;
			this.followDone = null;
			this.pending = null;
			this.listGeneration.incrementAndGet();
		}
	}

	private final Object indexLock = new Object();
	private volatile CompletableFuture<?> pendingIndex;

	/**
	 * Submits a search index rebuild unless one is already running.
	 * <p>
	 * The index build is the dominant startup cost in a large pack: every ingredient crossed with
	 * every prefix, computing display names, mod ids and searchable strings. It is keyed separately
	 * from query filtering because its dedupe rule is different, only ever one can be useful at a
	 * time, and it must be cancellable-by-supersession rather than dropped.
	 */
	public void submitIndex(Supplier<?> task, Consumer<Object> onDone) {
		synchronized (indexLock) {
			CompletableFuture<?> current = this.pendingIndex;
			if (current != null && !current.isDone()) {
				return;
			}
			long myGeneration = generation.incrementAndGet();
			this.pendingIndex = CompletableFuture.supplyAsync(() -> {
				Object result = task.get();
				if (myGeneration == generation.get()) {
					onDone.accept(result);
				}
				return result;
			}, AsyncPluginSupport.getExecutor());
			this.pendingIndex.exceptionally(t -> null);
		}
	}

	public boolean isIndexPending() {
		CompletableFuture<?> current = this.pendingIndex;
		return current != null && !current.isDone();
	}

	/** Convenience for tests and diagnostics. */
	public List<String> describeState() {
		return List.of(
			"generation=" + generation.get(),
			"listGeneration=" + listGeneration.get(),
			"submittedFilter=" + submittedFilter,
			"stale=" + stale,
			"pending=" + isPending()
		);
	}
}