package dev.jeasync;

import mezz.jei.api.search.ISearchStorage;
import mezz.jei.api.search.ISearchStorageBuilder;
import mezz.jei.common.config.SearchMode;
import mezz.jei.common.search.PrefixInfo;
import mezz.jei.common.search.PrefixedSearchable;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Future;

/**
 * Builds one JEI search prefix by dividing the ingredient list across the worker pool.
 * <p>
 * JEI builds one prefix at a time and walks the whole ingredient list for each one. JEAsync used to
 * build one prefix per worker instead, which leaves the tooltip prefix - the largest one by far,
 * because it calls {@code IIngredientRenderer.getTooltip} for every ingredient - on a single
 * thread while the rest of the pool idles.
 * <p>
 * This keeps one prefix in flight but splits its ingredients into small shards, so every worker
 * computes strings for a different part of the list. The shards are extracted on workers and
 * drained into the single {@code ISearchStorageBuilder} on the calling thread, because that builder
 * is single-use and not thread-safe (and a plugin can supply its own through the advanced search
 * registration, so its threading rules are unknown).
 * <p>
 * Only a bounded number of shards are submitted at a time. A shard holds its keys until the
 * consumer drains it, so without the window the whole prefix would sit in memory while the last
 * shards run - the same strings are allocated either way, but the window keeps the live set close
 * to the old sequential build instead of the entire prefix.
 * <p>
 * A prefix whose strings throw on a worker (plugin tooltip code is the usual source) is retried on
 * one thread. That keeps a renderer that is not safe to call concurrently from silently leaving the
 * prefix with an empty index.
 */
public final class SearchIndexBuild {
	private static final Logger LOGGER = LogManager.getLogger(SearchIndexBuild.class);

	/** Below this many ingredients the shard bookkeeping costs more than it saves. */
	private static final int MIN_PARALLEL_ELEMENTS = 512;

	/** Ingredients per shard. Small enough to balance uneven tooltip costs and bound the live set. */
	private static final int SHARD_ELEMENTS = 256;

	private static final long LOG_THRESHOLD_MILLIS = 25L;

	private SearchIndexBuild() {
	}

	/**
	 * Builds the searchable for {@code prefixInfo}. A disabled prefix keeps the shared empty
	 * storage instead of allocating a Lucene directory for it.
	 */
	public static PrefixedSearchable<IListElementInfo<?>, IListElement<?>> create(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		Collection<IListElementInfo<?>> infos
	) {
		if (prefixInfo.getMode() == SearchMode.DISABLED) {
			return new PrefixedSearchable<>(DeferredSearchStorage.get(), prefixInfo);
		}

		long started = System.nanoTime();
		List<IListElementInfo<?>> elements = new ArrayList<>(infos);
		int workers = workerCount(elements.size());
		int threadsUsed = 1;
		ISearchStorage<IListElement<?>> storage;
		if (workers > 1) {
			try {
				storage = buildParallel(prefixInfo, elements, workers);
				threadsUsed = workers;
			} catch (RuntimeException | LinkageError parallelFailure) {
				LOGGER.warn(
					"jeasync: the {} index failed while building on {} threads, retrying on one thread",
					prefixInfo, workers, parallelFailure
				);
				storage = buildSequential(prefixInfo, elements);
			}
		} else {
			storage = buildSequential(prefixInfo, elements);
		}

		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		if (elapsed >= LOG_THRESHOLD_MILLIS) {
			LOGGER.info(
				"Search index for prefix {} took {} ms over {} ingredients on {} thread(s)",
				prefixInfo, elapsed, elements.size(), threadsUsed
			);
		}
		return new PrefixedSearchable<>(storage, prefixInfo);
	}

	private static int workerCount(int elements) {
		if (elements < MIN_PARALLEL_ELEMENTS) {
			return 1;
		}
		int threads = AsyncConfig.getThreadCount();
		if (threads <= 1) {
			return 1;
		}
		int shards = (elements + SHARD_ELEMENTS - 1) / SHARD_ELEMENTS;
		return Math.min(threads, shards);
	}

	private static ISearchStorage<IListElement<?>> buildSequential(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		List<IListElementInfo<?>> infos
	) {
		ISearchStorageBuilder<IListElement<?>> builder = prefixInfo.createStorageBuilder();
		for (IListElementInfo<?> info : infos) {
			Collection<String> strings = prefixInfo.getStrings(info);
			IListElement<?> element = info.getElement();
			for (String string : strings) {
				String trimmed = string.trim();
				if (!trimmed.isEmpty()) {
					builder.put(trimmed, element);
				}
			}
		}
		return builder.build();
	}

	private static ISearchStorage<IListElement<?>> buildParallel(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		List<IListElementInfo<?>> infos,
		int workers
	) {
		int size = infos.size();
		int shardCount = (size + SHARD_ELEMENTS - 1) / SHARD_ELEMENTS;
		ISearchStorageBuilder<IListElement<?>> builder = prefixInfo.createStorageBuilder();
		CompletionService<Shard> completion =
			new ExecutorCompletionService<>(AsyncPluginSupport.getSearchBuildExecutor());

		// Futures hold their shard until they are dropped, so completed ones are removed as they
		// are drained. Keeping them in a list would pin every key computed so far.
		Set<Future<Shard>> inFlight = Collections.newSetFromMap(new IdentityHashMap<>());
		int window = Math.min(shardCount, workers * 2);
		int nextShard = 0;
		try {
			for (; nextShard < window; nextShard++) {
				inFlight.add(completion.submit(shard(prefixInfo, infos, nextShard, size)));
			}
			for (int consumed = 0; consumed < shardCount; consumed++) {
				Future<Shard> completed = take(completion);
				inFlight.remove(completed);
				Shard shard = resultOf(completed);
				shard.drainInto(builder);
				if (nextShard < shardCount) {
					inFlight.add(completion.submit(shard(prefixInfo, infos, nextShard, size)));
					nextShard++;
				}
			}
		} catch (RuntimeException | Error failure) {
			cancelAll(inFlight);
			throw failure;
		}
		return builder.build();
	}

	private static Callable<Shard> shard(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		List<IListElementInfo<?>> infos,
		int shardIndex,
		int size
	) {
		int from = shardIndex * SHARD_ELEMENTS;
		int to = Math.min(size, from + SHARD_ELEMENTS);
		return () -> extract(prefixInfo, infos, from, to);
	}

	private static Shard extract(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		List<IListElementInfo<?>> infos,
		int from,
		int to
	) {
		int expected = Math.max(1, (to - from) * 4);
		List<String> keys = new ArrayList<>(expected);
		List<IListElement<?>> elements = new ArrayList<>(expected);
		for (int i = from; i < to; i++) {
			IListElementInfo<?> info = infos.get(i);
			Collection<String> strings = prefixInfo.getStrings(info);
			IListElement<?> element = info.getElement();
			for (String string : strings) {
				String trimmed = string.trim();
				if (!trimmed.isEmpty()) {
					keys.add(trimmed);
					elements.add(element);
				}
			}
		}
		return new Shard(keys, elements);
	}

	private static Future<Shard> take(CompletionService<Shard> completion) {
		try {
			return completion.take();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("jeasync: interrupted while building a search index", e);
		}
	}

	private static Shard resultOf(Future<Shard> completed) {
		try {
			return completed.get();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("jeasync: interrupted while building a search index", e);
		} catch (ExecutionException e) {
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			if (cause instanceof RuntimeException runtime) {
				throw runtime;
			}
			if (cause instanceof Error error) {
				throw error;
			}
			throw new IllegalStateException(cause);
		}
	}

	private static void cancelAll(Set<Future<Shard>> inFlight) {
		for (Future<Shard> future : inFlight) {
			future.cancel(true);
		}
	}

	/** Keys and their elements for one shard, drained in extraction order by the caller. */
	private static final class Shard {
		private final List<String> keys;
		private final List<IListElement<?>> elements;

		private Shard(List<String> keys, List<IListElement<?>> elements) {
			this.keys = keys;
			this.elements = elements;
		}

		private void drainInto(ISearchStorageBuilder<IListElement<?>> builder) {
			int count = keys.size();
			for (int i = 0; i < count; i++) {
				builder.put(keys.get(i), elements.get(i));
			}
		}
	}
}
