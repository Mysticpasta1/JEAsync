package dev.jeiasync.mixin.search;

import dev.jeiasync.AsyncSearchCoordinator;
import mezz.jei.api.helpers.IModIdHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.config.IClientConfig;
import mezz.jei.gui.filter.IFilterTextSource;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.ingredients.IngredientListElementFactory;
import mezz.jei.gui.overlay.elements.IElement;
import mezz.jei.gui.overlay.elements.IngredientElement;
import mezz.jei.gui.overlay.ingredients.IIngredientGridSource.SourceListChangedListener;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearch;
import mezz.jei.gui.search.ElementSearchLowMem;
import mezz.jei.gui.search.IElementSearch;
import mezz.jei.gui.search.SearchTokenizer;
import mezz.jei.gui.search.Token;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Stream;

/**
 * Makes {@link mezz.jei.gui.ingredients.IngredientFilter} safe when ingredients are added
 * off-thread, and moves filtering off the render thread.
 * <p>
 * <b>Thread safety.</b> Because {@code IngredientManager.addIngredientsAtRuntime} no longer asserts
 * the main thread, {@code onIngredientsAdded} can now run on a plugin worker thread, racing the
 * render thread on the {@code listeners} list, the {@code ingredientListCached} list, the
 * {@code elementSearch} instance (reassigned wholesale by {@code rebuildItemFilter}) and the two
 * dirty flags. All of it goes under one monitor, with the listener list copied before notifying.
 * <p>
 * <b>Async filtering.</b> {@code getIngredientListUncached} sorts and visibility-checks the whole
 * ingredient list, which is too much to do mid-frame. It is dispatched to a worker that first
 * snapshots everything it needs under the monitor, then computes without holding it, so the render
 * thread is never blocked for the length of a search. {@link #getElements} keeps serving the
 * previous list until a newer one lands, so typing never blanks the ingredient list.
 * <p>
 * {@link IngredientElement} wrappers are built when a result is published rather than on the
 * worker, so the worker only does the parallelisable part: filtering and sorting.
 * <p>
 * Targets upstream 15.32.0.173.
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
	private SearchTokenizer searchTokenizer;

	@Shadow
	private ElementPrefixParser elementPrefixParser;

	@Shadow
	private IClientConfig clientConfig;

	@Shadow
	private IModIdHelper modIdHelper;

	@Shadow
	private Function<List<IListElementInfo<?>>, Comparator<IListElement<?>>> sortIndexUpdater;

	@Shadow
	private List<IElement<?>> ingredientListCached;

	@Shadow
	private boolean searchIndexDirty;

	@Shadow
	private boolean sortIndexesDirty;

	@Shadow
	private Comparator<IListElement<?>> ingredientComparator;

	// Added by this mixin. Deliberately left uninitialised: Mixin cannot safely inject a field
	// initialiser here, so it is created lazily instead.
	private AsyncSearchCoordinator jeiAsyncSearchCoordinator;

	@Shadow
	private <V> boolean updateHiddenState(IListElement<V> element) {
		throw new AssertionError();
	}

	@Overwrite
	private void updateDirtyState() {
		boolean rebuild;
		synchronized (this) {
			rebuild = searchIndexDirty || sortIndexesDirty;
		}
		// Upstream rebuilds the search index and the sort index in two separate passes, each of
		// which walks every ingredient. rebuildItemFilter recomputes both, so one pass is enough.
		if (rebuild) {
			rebuildItemFilter();
		}
	}

	@Overwrite
	public void rebuildItemFilter() {
		this.invalidateCache();

		IClientConfig clientConfigSnapshot;
		ElementPrefixParser parserSnapshot;
		IIngredientManager managerSnapshot;
		IModIdHelper modIdHelperSnapshot;
		Function<List<IListElementInfo<?>>, Comparator<IListElement<?>>> updaterSnapshot;
		IElementSearch searchSnapshot;
		synchronized (this) {
			clientConfigSnapshot = this.clientConfig;
			parserSnapshot = this.elementPrefixParser;
			managerSnapshot = this.ingredientManager;
			modIdHelperSnapshot = this.modIdHelper;
			updaterSnapshot = this.sortIndexUpdater;
			searchSnapshot = this.elementSearch;
		}

		jeiAsyncCoordinator().submitIndex(
			() -> {
				long started = System.nanoTime();
				Collection<IListElement<?>> ingredients = searchSnapshot.getAllIngredients();
				List<IListElementInfo<?>> elementInfos =
					IngredientListElementFactory.rebuildList(managerSnapshot, ingredients, modIdHelperSnapshot);
				Comparator<IListElement<?>> comparator = updaterSnapshot.apply(elementInfos);
				IElementSearch rebuilt = jeiAsyncCreateSearch(
					clientConfigSnapshot, parserSnapshot, elementInfos, managerSnapshot);
				return new JeiAsyncIndex(
					comparator, rebuilt, (System.nanoTime() - started) / 1_000_000L);
			},
			result -> {
				JeiAsyncIndex index = (JeiAsyncIndex) result;
				synchronized (IngredientFilterMixin.this) {
					ingredientComparator = index.comparator();
					elementSearch = index.search();
					searchIndexDirty = false;
					sortIndexesDirty = false;
				}
				org.apache.logging.log4j.LogManager.getLogger(IngredientFilterMixin.class)
					.info("Async search index build took {} ms", index.elapsedMillis());
				notifyListenersOfChange();
			}
		);
	}

	private record JeiAsyncIndex(Comparator<IListElement<?>> comparator, IElementSearch search, long elapsedMillis) {
	}

	private static IElementSearch jeiAsyncCreateSearch(
		IClientConfig clientConfig,
		ElementPrefixParser parser,
		List<IListElementInfo<?>> ingredients,
		IIngredientManager ingredientManager
	) {
		if (clientConfig.isLowMemorySlowSearchEnabled()) {
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

	@Overwrite
	public <V> void addIngredient(IListElementInfo<V> info) {
		IListElement<V> element = info.getElement();
		synchronized (this) {
			updateHiddenState(element);
			this.elementSearch.add(info, ingredientManager);
			ingredientListCached = null;
		}
		// The ingredient set changed, so anything in flight is working from stale input.
		jeiAsyncCoordinator().invalidate();
		notifyListenersOfChange();
	}

	@Overwrite
	public void invalidateCache() {
		synchronized (this) {
			ingredientListCached = null;
		}
		jeiAsyncCoordinator().invalidate();
	}

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

	@Overwrite
	public void addSourceListChangedListener(SourceListChangedListener listener) {
		synchronized (this) {
			listeners.add(listener);
		}
	}

	@Overwrite
	private void markSearchIndexDirty() {
		synchronized (this) {
			this.searchIndexDirty = true;
		}
		notifyListenersOfChange();
	}

	@Overwrite
	private void markSortIndexesDirty() {
		synchronized (this) {
			this.sortIndexesDirty = true;
		}
		notifyListenersOfChange();
	}

	@Overwrite
	public List<IElement<?>> getElements() {
		// Rebuild any pending index work first, exactly as upstream does.
		updateDirtyState();

		String filterText = this.filterTextSource.getFilterText().toLowerCase(Locale.ROOT);

		List<IElement<?>> ready;
		IElementSearch searchSnapshot;
		Comparator<IListElement<?>> comparatorSnapshot;
		SearchTokenizer tokenizerSnapshot;
		ElementPrefixParser parserSnapshot;
		synchronized (this) {
			ready = ingredientListCached;
			searchSnapshot = this.elementSearch;
			comparatorSnapshot = this.ingredientComparator;
			tokenizerSnapshot = this.searchTokenizer;
			parserSnapshot = this.elementPrefixParser;
		}

		jeiAsyncCoordinator().submit(
			filterText,
			() -> jeiAsyncCompute(searchSnapshot, comparatorSnapshot, tokenizerSnapshot, parserSnapshot, filterText),
			result -> {
				@SuppressWarnings("unchecked")
				List<ITypedIngredient<?>> typed = (List<ITypedIngredient<?>>) result;
				synchronized (IngredientFilterMixin.this) {
					ingredientListCached = typed.stream().<IElement<?>>map(IngredientElement::new).toList();
				}
				notifyListenersOfChange();
			}
		);

		// Serve the previous list until a newer one is published. Empty only before the very first
		// result of a fresh session.
		return ready != null ? ready : List.of();
	}

	/**
	 * Lock-free equivalent of upstream's {@code getIngredientListUncached}, working from the
	 * snapshots the caller took rather than reading mutable fields.
	 */
	private static List<ITypedIngredient<?>> jeiAsyncCompute(
		IElementSearch search,
		Comparator<IListElement<?>> comparator,
		SearchTokenizer tokenizer,
		ElementPrefixParser parser,
		String filterText
	) {
		String[] filters = filterText.split("\\|");
		List<JeiAsyncSearchTokens> searchTokens = Arrays.stream(filters)
			.map(f -> jeiAsyncParseTokens(tokenizer, parser, f))
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

	private record JeiAsyncSearchTokens(List<ElementPrefixParser.TokenInfo> toSearch, List<ElementPrefixParser.TokenInfo> toRemove) {
		boolean isEmpty() {
			return toSearch.isEmpty() && toRemove.isEmpty();
		}
	}

	private static JeiAsyncSearchTokens jeiAsyncParseTokens(SearchTokenizer tokenizer, ElementPrefixParser parser, String filterText) {
		List<ElementPrefixParser.TokenInfo> toSearch = new ArrayList<>();
		List<ElementPrefixParser.TokenInfo> toRemove = new ArrayList<>();

		if (filterText.isEmpty()) {
			return new JeiAsyncSearchTokens(toSearch, toRemove);
		}

		for (Token token : tokenizer.tokenize(filterText)) {
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
		return new JeiAsyncSearchTokens(toSearch, toRemove);
	}

	private static Set<IListElement<?>> jeiAsyncSearchResults(IElementSearch search, JeiAsyncSearchTokens searchTokens) {
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