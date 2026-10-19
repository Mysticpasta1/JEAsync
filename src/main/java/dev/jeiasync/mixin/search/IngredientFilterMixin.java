package dev.jeiasync.mixin.search;

import dev.jeiasync.AsyncPluginSupport;
import dev.jeiasync.AsyncSearchCoordinator;
import dev.jeiasync.FilterWork;
import dev.jeiasync.RuntimeIngredientUpdates;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.common.config.IIngredientFilterConfig;
import mezz.jei.common.util.Translator;
import mezz.jei.gui.filter.IFilterTextSource;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.ingredients.IngredientListElementFactory;
import mezz.jei.gui.ingredients.ListElementInfo;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.elements.IngredientElement;
import mezz.jei.gui.overlay.ingredients.IIngredientGridSource.SourceListChangedListener;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearch;
import mezz.jei.gui.search.ElementSearchIndex;
import mezz.jei.gui.search.ElementSearchLowMem;
import mezz.jei.gui.search.IElementSearch;
import mezz.jei.gui.search.SearchTokenizer;
import mezz.jei.gui.search.Token;
import net.minecraft.client.Minecraft;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Makes {@link mezz.jei.gui.ingredients.IngredientFilter} safe when ingredients are added off-thread,
 * and moves filtering off the render thread.
 * <p>
 * Listeners, the cached lists, the search index, and the dirty flags share one monitor. Listeners
 * are copied before they are notified. {@code getElements} snapshots under that monitor, filters on
 * a worker, and keeps serving the previous list until the new one is published. The render thread
 * returns that warmed list, so opening the inventory does not sort or regroup the ingredient list.
 * <p>
 * {@link #rebuildItemFilter} builds the prefix index on a worker. One build at a time is enough, so
 * it has its own dedupe state. A config change that dirties only some indexes is queued on that
 * same pool when {@code getElements} runs on the render thread.
 * {@link IngredientElement} wrappers are built when a result is published, not during the sort.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.gui.ingredients.IngredientFilter.class, remap = false)
public abstract class IngredientFilterMixin {
	@Shadow
	private List<SourceListChangedListener> listeners;

	@Shadow
	private IElementSearch elementSearch;

	@Shadow
	private IFilterTextSource filterTextSource;

	@Shadow
	private IIngredientManager ingredientManager;

	@Shadow
	private IIngredientFilterConfig config;

	@Shadow
	private ElementPrefixParser elementPrefixParser;

	@Shadow
	private IModIdHelper modIdHelper;

	@Shadow
	private Function<List<IListElementInfo<?>>, Comparator<IListElement<?>>> sortIndexUpdater;

	@Shadow
	private List<IElement<?>> ingredientListCached;

	@Shadow
	private List<IListElementInfo<?>> elementInfoCache;

	@Shadow
	private Set<ElementSearchIndex> dirtySearchIndexes;

	@Shadow
	private boolean searchImplementationDirty;

	@Shadow
	private boolean sortIndexesDirty;

	@Shadow
	private long completionRevision;

	@Shadow
	private Comparator<IListElement<?>> ingredientComparator;

	// Added by this mixin. Deliberately left uninitialised: Mixin cannot safely inject a field
	// initialiser here, so it is created lazily instead.
	private AsyncSearchCoordinator jeiAsyncSearchCoordinator;

	// Same constraint as the coordinator. Queues tooltip indexing for runtime ingredient adds.
	private RuntimeIngredientUpdates jeiAsyncRuntimeUpdates;

	// Created lazily. Mixin field initialisers are not applied to the target class.
	private ReentrantLock jeiAsyncInstallLock;
	private ThreadLocal<List<IElement<?>>> jeiAsyncWarmThread;
	private volatile List<IElement<?>> jeiAsyncWarmed;
	private long jeiAsyncPublishedGeneration;
	private List<IElement<?>> jeiAsyncRewarmTarget;
	private boolean jeiAsyncRewarmQueued;
	private volatile boolean jeiAsyncDirtyQueued;

	@Shadow
	private <V> boolean updateHiddenState(IListElement<V> element) {
		throw new AssertionError();
	}

	@Shadow
	private IClientConfig clientConfig;

	/**
	 * @author Mysticpasta1
	 * @reason Take the dirty flags under one lock, and keep index rebuilds off the render thread.
	 */
	@Overwrite
	private void updateDirtyState() {
		if (jeiAsyncOnRenderThread() && jeiAsyncPartialDirty()) {
			jeiAsyncQueueDirty();
			return;
		}
		jeiAsyncApplyDirtyState();
	}

	/** True when a config change dirtied indexes or the sort and a full filter rebuild is not pending. */
	private boolean jeiAsyncPartialDirty() {
		synchronized (this) {
			return !searchImplementationDirty && (!dirtySearchIndexes.isEmpty() || sortIndexesDirty);
		}
	}

	private void jeiAsyncQueueDirty() {
		synchronized (this) {
			if (jeiAsyncDirtyQueued) {
				return;
			}
			jeiAsyncDirtyQueued = true;
		}
		AsyncPluginSupport.getExecutor().execute(() -> {
			boolean finished = false;
			try {
				jeiAsyncApplyDirtyState();
				finished = true;
			} catch (RuntimeException e) {
				org.apache.logging.log4j.LogManager.getLogger(IngredientFilterMixin.class)
					.warn("JEI Async: search index refresh failed", e);
			} finally {
				boolean more = false;
				synchronized (this) {
					if (finished) {
						more = searchImplementationDirty || !dirtySearchIndexes.isEmpty() || sortIndexesDirty;
					}
					jeiAsyncDirtyQueued = false;
				}
				if (more) {
					jeiAsyncQueueDirty();
				}
			}
		});
	}

	/**
	 * The body of {@link #updateDirtyState}. Callers on the render thread queue this instead of
	 * running the ingredient walk while a frame is drawn.
	 */
	private void jeiAsyncApplyDirtyState() {
		boolean fullRebuild;
		boolean indexRebuild;
		boolean sortRebuild;
		synchronized (this) {
			fullRebuild = searchImplementationDirty;
			indexRebuild = !fullRebuild && !dirtySearchIndexes.isEmpty();
			sortRebuild = sortIndexesDirty;
		}

		if (fullRebuild) {
			// rebuildItemFilter recomputes every index and the comparator, so it also covers
			// whatever else was dirty.
			rebuildItemFilter();
			return;
		}
		if (!indexRebuild && !sortRebuild) {
			return;
		}

		List<IListElementInfo<?>> elementInfos = null;
		if (indexRebuild) {
			elementInfos = jeiAsyncRebuildList();
			synchronized (this) {
				Set<ElementSearchIndex> indexes = EnumSet.copyOf(dirtySearchIndexes);
				dirtySearchIndexes.clear();
				this.elementSearch.rebuildSearchIndexes(indexes, elementInfos);
			}
			invalidateCache();
			invalidateCandidateCache();
		}

		if (sortRebuild) {
			if (elementInfos == null) {
				elementInfos = jeiAsyncRebuildList();
			}
			Comparator<IListElement<?>> comparator = sortIndexUpdater.apply(elementInfos);
			synchronized (this) {
				ingredientComparator = comparator;
				sortIndexesDirty = false;
			}
			invalidateCache();
		}
	}

	/**
	 * Snapshot-then-rebuild, so the render thread only holds the monitor long enough to copy the
	 * handful of fields this needs rather than for the whole cross-product build.
	 */
	private List<IListElementInfo<?>> jeiAsyncRebuildList() {
		IElementSearch searchSnapshot;
		IIngredientManager managerSnapshot;
		IIngredientFilterConfig configSnapshot;
		IModIdHelper modIdHelperSnapshot;
		synchronized (this) {
			searchSnapshot = this.elementSearch;
			managerSnapshot = this.ingredientManager;
			configSnapshot = this.config;
			modIdHelperSnapshot = this.modIdHelper;
		}
		Collection<IListElement<?>> ingredients = searchSnapshot.getAllIngredients();
		return IngredientListElementFactory.rebuildList(managerSnapshot, ingredients, configSnapshot, modIdHelperSnapshot);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Rebuild the search index on a worker so startup does not block the render thread.
	 */
	@Overwrite
	public void rebuildItemFilter() {
		this.invalidateCache();

		FilterWork.Config configSnapshot;
		ElementPrefixParser parserSnapshot;
		IIngredientManager managerSnapshot;
		IModIdHelper modIdHelperSnapshot;
		Function<List<IListElementInfo<?>>, Comparator<IListElement<?>>> updaterSnapshot;
		IElementSearch searchSnapshot;
		synchronized (this) {
			configSnapshot = new FilterWork.Config(this.clientConfig, this.config);
			parserSnapshot = this.elementPrefixParser;
			managerSnapshot = this.ingredientManager;
			modIdHelperSnapshot = this.modIdHelper;
			updaterSnapshot = this.sortIndexUpdater;
			searchSnapshot = this.elementSearch;
			dirtySearchIndexes.clear();
			searchImplementationDirty = false;
			sortIndexesDirty = false;
			invalidateCandidateCache();
		}

		jeiAsyncCoordinator().submitIndex(
			() -> {
				long started = System.nanoTime();
				Collection<IListElement<?>> ingredients = searchSnapshot.getAllIngredients();
				List<IListElementInfo<?>> elementInfos =
					IngredientListElementFactory.rebuildList(managerSnapshot, ingredients, configSnapshot.filterConfig(), modIdHelperSnapshot);
				Comparator<IListElement<?>> comparator = updaterSnapshot.apply(elementInfos);
				IElementSearch rebuilt = jeiAsyncCreateSearch(
					configSnapshot, parserSnapshot, elementInfos, managerSnapshot);
				return new FilterWork.Index(
					comparator, rebuilt, (System.nanoTime() - started) / 1_000_000L);
			},
			result -> {
				FilterWork.Index index = (FilterWork.Index) result;
				synchronized (IngredientFilterMixin.this) {
					ingredientComparator = index.comparator();
					elementSearch = index.search();
				}
				org.apache.logging.log4j.LogManager.getLogger(IngredientFilterMixin.class)
					.info("Async search index build took {} ms", index.elapsedMillis());
				// The sort scheduled at the start of this rebuild read the previous search.
				AsyncSearchCoordinator coordinator = jeiAsyncCoordinator();
				coordinator.invalidate();
				jeiAsyncScheduleElements();
				notifyListenersOfChange();
			}
		);
	}

	private static IElementSearch jeiAsyncCreateSearch(
		FilterWork.Config config,
		ElementPrefixParser parser,
		List<IListElementInfo<?>> ingredients,
		IIngredientManager ingredientManager
	) {
		if (config.lowMemorySlowSearchEnabled()) {
			return new ElementSearchLowMem(parser.getNoPrefix(), ingredients);
		}
		return new ElementSearch(parser, ingredients, ingredientManager);
	}

	private AsyncSearchCoordinator jeiAsyncCoordinator() {
		synchronized (this) {
			if (jeiAsyncSearchCoordinator == null) {
				jeiAsyncSearchCoordinator = new AsyncSearchCoordinator();
			}
			return jeiAsyncSearchCoordinator;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Update the hidden flag under the filter lock, then index the ingredient without holding that lock.
	 */
	@Overwrite
	public <V> void addIngredient(IListElementInfo<V> info) {
		IListElement<V> element = info.getElement();
		synchronized (this) {
			updateHiddenState(element);
		}
		// Tooltip strings and the suffix-tree insert are the slow part. Holding the filter monitor
		// across them stalls every other runtime add and the ingredient grid.
		this.elementSearch.add(info, ingredientManager);
		this.invalidateCache();
		this.invalidateCandidateCache();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Index a runtime batch off the plugin thread and refresh the filtered list once.
	 */
	@Overwrite
	public <V> void onIngredientsAdded(IIngredientHelper<V> ingredientHelper, Collection<ITypedIngredient<V>> ingredients) {
		if (ingredients.isEmpty()) {
			return;
		}
		// The caller can reuse the collection after this returns.
		List<ITypedIngredient<V>> batch = List.copyOf(ingredients);
		jeiAsyncRuntimeQueue().submit(() -> jeiAsyncIndexAdded(ingredientHelper, batch));
	}

	private RuntimeIngredientUpdates jeiAsyncRuntimeQueue() {
		synchronized (this) {
			if (jeiAsyncRuntimeUpdates == null) {
				jeiAsyncRuntimeUpdates = new RuntimeIngredientUpdates();
			}
			return jeiAsyncRuntimeUpdates;
		}
	}

	/**
	 * Same result as upstream's per-ingredient listener, applied as one batch. Existing elements
	 * only refresh their hidden flag. New ones are wrapped and inserted into the live search.
	 */
	private <V> void jeiAsyncIndexAdded(IIngredientHelper<V> ingredientHelper, List<ITypedIngredient<V>> ingredients) {
		long started = System.nanoTime();
		IIngredientManager manager;
		IIngredientFilterConfig filterConfig;
		IModIdHelper mods;
		synchronized (this) {
			manager = this.ingredientManager;
			filterConfig = this.config;
			mods = this.modIdHelper;
		}
		int indexed = 0;
		for (ITypedIngredient<V> ingredient : ingredients) {
			try {
				Optional<IListElement<V>> existing = this.elementSearch.findElement(ingredient, ingredientHelper);
				if (existing.isPresent()) {
					IListElement<V> element = existing.get();
					synchronized (this) {
						updateHiddenState(element);
					}
					continue;
				}
				IListElementInfo<V> info = ListElementInfo.create(ingredient, manager, filterConfig, mods);
				if (info == null) {
					continue;
				}
				synchronized (this) {
					updateHiddenState(info.getElement());
				}
				this.elementSearch.add(info, manager);
				indexed++;
			} catch (RuntimeException e) {
				org.apache.logging.log4j.LogManager.getLogger(IngredientFilterMixin.class)
					.warn("JEI Async: skipped a runtime ingredient that could not be indexed", e);
			}
		}
		this.invalidateCache();
		this.invalidateCandidateCache();
		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		if (elapsed >= 25) {
			org.apache.logging.log4j.LogManager.getLogger(IngredientFilterMixin.class)
				.info("Runtime search update indexed {} ingredients in {} ms", indexed, elapsed);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Keep the visible list and refresh it on a worker when the ingredient set changes.
	 */
	@Overwrite
	public void invalidateCache() {
		List<IElement<?>> keep;
		synchronized (this) {
			keep = ingredientListCached;
		}
		jeiAsyncCoordinator().invalidate();
		jeiAsyncQueueRewarm(keep);
		jeiAsyncScheduleElements();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Drop the candidate cache under a lock so a worker does not publish a stale completion list.
	 */
	@Overwrite
	private void invalidateCandidateCache() {
		synchronized (this) {
			elementInfoCache = null;
			completionRevision++;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Copy the listener list before notifying so a listener can register another without a concurrent modification.
	 */
	@Overwrite
	private void notifyListenersOfChange() {
		List<SourceListChangedListener> snapshot;
		synchronized (this) {
			snapshot = new ArrayList<>(listeners);
		}
		for (SourceListChangedListener listener : snapshot) {
			listener.onSourceListChanged();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock listener registration against notification from the filter worker.
	 */
	@Overwrite
	public void addSourceListChangedListener(SourceListChangedListener listener) {
		synchronized (this) {
			listeners.add(listener);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the dirty-index set so a config change does not race the render thread.
	 */
	@Overwrite
	private void markSearchIndexDirty(ElementSearchIndex searchIndex) {
		synchronized (this) {
			this.dirtySearchIndexes.add(searchIndex);
		}
		notifyListenersOfChange();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the implementation-dirty flag so a config change does not race the render thread.
	 */
	@Overwrite
	private void markSearchImplementationDirty() {
		synchronized (this) {
			this.searchImplementationDirty = true;
		}
		notifyListenersOfChange();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the sort-dirty flag so a config change does not race the render thread.
	 */
	@Overwrite
	private void markSortIndexesDirty() {
		synchronized (this) {
			this.sortIndexesDirty = true;
		}
		notifyListenersOfChange();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Rebuild or return the cached infos under a lock so two threads do not each see a partial cache.
	 */
	@Overwrite
	public Collection<IListElementInfo<?>> getAllElementInfos() {
		updateDirtyState();
		synchronized (this) {
			if (elementInfoCache != null) {
				return elementInfoCache;
			}
		}
		List<IListElementInfo<?>> infos = jeiAsyncRebuildList();
		synchronized (this) {
			elementInfoCache = infos;
			return elementInfoCache;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Serve the warmed filtered list and compute the next one off the render thread.
	 */
	@Overwrite
	public List<IElement<?>> getElements() {
		List<IElement<?>> warming = jeiAsyncWarmingPeek();
		if (warming != null) {
			return warming;
		}
		updateDirtyState();
		jeiAsyncScheduleElements();

		List<IElement<?>> warmed = jeiAsyncWarmed;
		if (jeiAsyncOnRenderThread()) {
			// The warmed list is the one already passed through getElements on a worker, so a
			// mod that transforms the returned list can reuse that result.
			return warmed != null ? warmed : List.of();
		}
		if (warmed != null) {
			return warmed;
		}
		synchronized (this) {
			return ingredientListCached != null ? ingredientListCached : List.of();
		}
	}

	private void jeiAsyncScheduleElements() {
		String filterText = Translator.toLowercaseWithLocale(this.filterTextSource.getFilterText());
		IElementSearch searchSnapshot;
		Comparator<IListElement<?>> comparatorSnapshot;
		ElementPrefixParser parserSnapshot;
		synchronized (this) {
			searchSnapshot = this.elementSearch;
			comparatorSnapshot = this.ingredientComparator;
			parserSnapshot = this.elementPrefixParser;
		}
		if (searchSnapshot == null || comparatorSnapshot == null || parserSnapshot == null) {
			return;
		}
		AsyncSearchCoordinator coordinator = jeiAsyncCoordinator();
		coordinator.submit(
			filterText,
			() -> {
				long started = System.nanoTime();
				List<ITypedIngredient<?>> typed = jeiAsyncCompute(
					searchSnapshot, comparatorSnapshot, parserSnapshot, filterText);
				long elapsed = (System.nanoTime() - started) / 1_000_000L;
				return new FilterWork.Filtered(typed, elapsed);
			},
			(generation, result) -> jeiAsyncPublishList(coordinator, generation, (FilterWork.Filtered) result)
		);
	}

	private void jeiAsyncPublishList(AsyncSearchCoordinator coordinator, long generation, FilterWork.Filtered filtered) {
		synchronized (this) {
			if (ingredientListCached != null && jeiAsyncPublishedGeneration > generation) {
				return;
			}
			if (ingredientListCached != null && !coordinator.isCurrent(generation)) {
				return;
			}
		}
		List<IElement<?>> elements = filtered.ingredients().stream().<IElement<?>>map(IngredientElement::new).toList();
		if (!jeiAsyncInstallPublished(coordinator, generation, elements)) {
			return;
		}
		if (filtered.elapsedMillis() >= 25) {
			org.apache.logging.log4j.LogManager.getLogger(IngredientFilterMixin.class)
				.info("Ingredient list ready in {} ms ({} ingredients)", filtered.elapsedMillis(), elements.size());
		}
		notifyListenersOfChange();
	}

	/**
	 * Pass {@code elements} through {@link #getElements()} on this worker so transforms that hook
	 * the return value run here, then publish that same list for the render thread.
	 *
	 * @return false when a newer list was already published
	 */
	private boolean jeiAsyncInstallPublished(AsyncSearchCoordinator coordinator, long generation, List<IElement<?>> elements) {
		ReentrantLock lock = jeiAsyncInstallLock();
		lock.lock();
		try {
			synchronized (this) {
				if (ingredientListCached != null && jeiAsyncPublishedGeneration > generation) {
					return false;
				}
				if (ingredientListCached != null && !coordinator.isCurrent(generation)) {
					return false;
				}
			}
			jeiAsyncWarm(elements);
			synchronized (this) {
				ingredientListCached = elements;
				jeiAsyncWarmed = elements;
				if (generation > jeiAsyncPublishedGeneration) {
					jeiAsyncPublishedGeneration = generation;
				}
			}
			return true;
		} finally {
			lock.unlock();
		}
	}

	private void jeiAsyncQueueRewarm(@Nullable List<IElement<?>> keep) {
		if (keep == null) {
			return;
		}
		synchronized (this) {
			jeiAsyncRewarmTarget = keep;
			if (jeiAsyncRewarmQueued) {
				return;
			}
			jeiAsyncRewarmQueued = true;
		}
		AsyncPluginSupport.getExecutor().execute(this::jeiAsyncRewarmLoop);
	}

	/**
	 * JEI++ clears its transform cache when {@link #invalidateCache()} returns. Running
	 * {@link #getElements()} here, on a worker, fills that cache again with the list already
	 * on screen so the render thread does not walk every ingredient when the inventory opens.
	 */
	private void jeiAsyncRewarmLoop() {
		while (true) {
			List<IElement<?>> target;
			synchronized (this) {
				target = jeiAsyncRewarmTarget;
				jeiAsyncRewarmTarget = null;
			}
			if (target != null) {
				boolean stillCurrent;
				synchronized (this) {
					stillCurrent = ingredientListCached == target;
				}
				if (stillCurrent) {
					jeiAsyncInstallRewarm(target);
				}
			}
			synchronized (this) {
				if (jeiAsyncRewarmTarget != null) {
					continue;
				}
				jeiAsyncRewarmQueued = false;
				return;
			}
		}
	}

	private void jeiAsyncInstallRewarm(List<IElement<?>> elements) {
		ReentrantLock lock = jeiAsyncInstallLock();
		lock.lock();
		try {
			synchronized (this) {
				if (ingredientListCached != elements) {
					return;
				}
			}
			jeiAsyncWarm(elements);
			synchronized (this) {
				if (ingredientListCached == elements) {
					jeiAsyncWarmed = elements;
				}
			}
		} catch (RuntimeException e) {
			org.apache.logging.log4j.LogManager.getLogger(IngredientFilterMixin.class)
				.warn("JEI Async: could not warm the ingredient list", e);
		} finally {
			lock.unlock();
		}
	}

	private void jeiAsyncWarm(List<IElement<?>> elements) {
		ThreadLocal<List<IElement<?>>> local = jeiAsyncWarmThread();
		local.set(elements);
		try {
			getElements();
		} finally {
			local.remove();
		}
	}

	private List<IElement<?>> jeiAsyncWarmingPeek() {
		ThreadLocal<List<IElement<?>>> local = this.jeiAsyncWarmThread;
		return local == null ? null : local.get();
	}

	private ReentrantLock jeiAsyncInstallLock() {
		synchronized (this) {
			if (jeiAsyncInstallLock == null) {
				jeiAsyncInstallLock = new ReentrantLock();
			}
			return jeiAsyncInstallLock;
		}
	}

	private ThreadLocal<List<IElement<?>>> jeiAsyncWarmThread() {
		synchronized (this) {
			if (jeiAsyncWarmThread == null) {
				jeiAsyncWarmThread = new ThreadLocal<>();
			}
			return jeiAsyncWarmThread;
		}
	}

	private static boolean jeiAsyncOnRenderThread() {
		Minecraft minecraft = Minecraft.getInstance();
		return minecraft != null && minecraft.isSameThread();
	}

	/**
	 * Lock-free equivalent of upstream's {@code getIngredientListUncached}, working from the
	 * snapshots the caller took rather than reading mutable fields.
	 */
	private static List<ITypedIngredient<?>> jeiAsyncCompute(
		IElementSearch search,
		Comparator<IListElement<?>> comparator,
		ElementPrefixParser parser,
		String filterText
	) {
		List<FilterWork.Tokens> searchTokens = SearchTokenizer.splitByOperators(SearchTokenizer.tokenize(filterText)).stream()
			.map(tokens -> jeiAsyncParseTokens(parser, tokens))
			.filter(s -> !s.isEmpty())
			.toList();

		Stream<IListElement<?>> elementStream;
		if (searchTokens.isEmpty()) {
			elementStream = search.getAllIngredients().parallelStream();
		} else {
			elementStream = searchTokens.stream()
				.map(s -> jeiAsyncSearchResults(search, s))
				.flatMap(Set::stream)
				.distinct();
		}

		return elementStream
			.filter(IListElement::isVisible)
			.sorted(comparator)
			.<ITypedIngredient<?>>map(IListElement::getTypedIngredient)
			.toList();
	}

	private static FilterWork.Tokens jeiAsyncParseTokens(ElementPrefixParser parser, List<Token> tokens) {
		List<ElementPrefixParser.TokenInfo> toSearch = new ArrayList<>();
		List<ElementPrefixParser.TokenInfo> toRemove = new ArrayList<>();

		for (Token token : tokens) {
			if (token.isEmpty()) {
				continue;
			}
			parser.parseToken(token.text()).ifPresent(result -> {
				if (token.exclusion()) {
					toRemove.add(result);
				} else {
					toSearch.add(result);
				}
			});
		}
		return new FilterWork.Tokens(toSearch, toRemove);
	}

	private static Set<IListElement<?>> jeiAsyncSearchResults(IElementSearch search, FilterWork.Tokens searchTokens) {
		List<Set<IListElement<?>>> resultsPerToken = searchTokens.toSearch().stream()
			.map(search::getSearchResults)
			.toList();
		Set<IListElement<?>> results = jeiAsyncIntersection(resultsPerToken);

		if (results.isEmpty() && !searchTokens.toRemove().isEmpty()) {
			results.addAll(search.getAllIngredients());
		}

		if (!results.isEmpty() && !searchTokens.toRemove().isEmpty()) {
			for (ElementPrefixParser.TokenInfo tokenInfo : searchTokens.toRemove()) {
				results.removeAll(search.getSearchResults(tokenInfo));
				if (results.isEmpty()) {
					break;
				}
			}
		}
		return results;
	}

	private static <T> Set<T> jeiAsyncIntersection(List<Set<T>> sets) {
		@Nullable
		Set<T> smallestSet = sets.stream()
			.min(Comparator.comparing(Set::size))
			.orElseGet(Set::of);

		Set<T> results = Collections.newSetFromMap(new IdentityHashMap<>());
		results.addAll(smallestSet);

		for (Set<T> set : sets) {
			if (set == smallestSet) {
				continue;
			}
			if (results.retainAll(set) && results.isEmpty()) {
				break;
			}
		}
		return results;
	}
}
