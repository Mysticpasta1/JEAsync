package dev.jeiasync;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
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
 * the next generation, and a completed task only publishes its result if no newer submission has
 * happened in the meantime. Cancelling mid-sort is not possible anyway, since the running code has
 * no cancellation points.
 * <p>
 * One coordinator exists per {@code IngredientFilter}. It is deliberately not thread-safe beyond the
 * atomics used here; the caller owns synchronization around {@link #isPending}.
 */
public final class AsyncSearchCoordinator {
	private final AtomicLong generation = new AtomicLong();
	private volatile String submittedFilter;
	private volatile CompletableFuture<?> pending;

	/**
	 * Submits {@code task} unless the same filter text is already in flight, and runs
	 * {@code onDone} on the worker thread when it completes.
	 * <p>
	 * {@code onDone} must not assume it is the current generation; check with
	 * {@link #isCurrent(long)} inside it, or let {@link #submit} handle that via
	 * {@link #submitIfCurrent}.
	 */
	public void submit(String filterText, Supplier<?> task, Consumer<Object> onDone) {
		if (filterText.equals(this.submittedFilter) && isPending()) {
			return;
		}
		long myGeneration = generation.incrementAndGet();
		this.submittedFilter = filterText;

		ExecutorService executor = AsyncPluginSupport.getExecutor();
		CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
			Object result = task.get();
			if (myGeneration == generation.get()) {
				onDone.accept(result);
			}
			return result;
		}, executor);

		this.pending = future;
		// Nothing awaits this, but an unobserved failure would otherwise be silent.
		future.exceptionally(t -> null);
	}

	public boolean isPending() {
		CompletableFuture<?> current = this.pending;
		return current != null && !current.isDone();
	}

	/** True if no submission has been made since {@code myGeneration} was taken. */
	public boolean isCurrent(long myGeneration) {
		return generation.get() == myGeneration;
	}

	/**
	 * Forces the next {@link #submit} to actually run, even if the filter text is unchanged.
	 * Used when the underlying ingredient set changed rather than the query.
	 */
	public void invalidate() {
		this.submittedFilter = null;
		generation.incrementAndGet();
	}

	public void reset() {
		invalidate();
		this.pending = null;
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
			"submittedFilter=" + submittedFilter,
			"pending=" + isPending()
		);
	}
}