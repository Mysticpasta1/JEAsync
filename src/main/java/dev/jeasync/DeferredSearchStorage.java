package dev.jeasync;

import mezz.jei.api.search.ISearchStorage;

import java.util.Collection;
import java.util.function.Consumer;

/**
 * Stands in for a search prefix that is not built yet, or whose mode is disabled.
 * <p>
 * An empty Lucene index still allocates a directory, an index writer, and a query cache. Those
 * objects used to sit next to the recipe tables for every heavy prefix. This storage does not.
 * {@link #supportsDeletion()} stays false so a later update rebuilds a real index instead of
 * writing into the placeholder.
 */
public final class DeferredSearchStorage<T> implements ISearchStorage<T> {
	private static final DeferredSearchStorage<Object> EMPTY = new DeferredSearchStorage<>();

	private DeferredSearchStorage() {
	}

	@SuppressWarnings("unchecked")
	public static <T> ISearchStorage<T> get() {
		return (ISearchStorage<T>) EMPTY;
	}

	@Override
	public void getSearchResults(String token, Consumer<Collection<T>> resultsConsumer) {
	}

	@Override
	public void getAllElements(Consumer<Collection<T>> resultsConsumer) {
	}

	@Override
	public void put(String key, T value) {
	}

	@Override
	public boolean supportsDeletion() {
		return false;
	}

	@Override
	public String statistics() {
		return "deferred";
	}
}
