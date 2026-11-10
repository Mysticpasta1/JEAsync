package dev.jeasync.mixin.search;

import dev.jeasync.DeferredSearchStorage;
import dev.jeasync.JeiLoadProgress;
import dev.jeasync.RuntimeIngredientUpdates;
import dev.jeasync.SearchIndexBuild;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.search.ISearchStorage;
import mezz.jei.common.config.SearchMode;
import mezz.jei.common.search.CombinedSearchables;
import mezz.jei.common.search.PrefixInfo;
import mezz.jei.common.search.PrefixedSearchable;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementSearch;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearchIndex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Makes {@link ElementSearch}'s lookups safe while recipes are still being registered.
 * <p>
 * {@code allElements} and {@code prefixedSearchables} are plain {@code HashMap}s, and the
 * {@code ISearchStorage}s hanging off them are written while registration runs. They cannot be swapped
 * for concurrent ones because they are {@code final} fields, so everything here goes under the
 * {@code allElements} monitor instead - reads as well as writes, because a search iterating a storage
 * that another thread is adding to throws {@code ConcurrentModificationException}.
 * <p>
 * That also covers {@link #rebuildSearchIndexes}, which since 15.71 replaces entries in
 * {@code prefixedSearchables} and swaps {@code combinedSearchables} wholesale from a config
 * listener. One monitor for the whole class is a coarse choice, but it is only contended during
 * startup and by a search in progress, and it removes any question of ordering between the two maps.
 * <p>
 * The index build itself no longer runs under that monitor. Each prefix is built outside the locks
 * and only installed under them, so a search that arrives mid-build keeps reading the previous
 * index instead of blocking until the new one is finished.
 * <p>
 * The live suffix tree has its own monitor, {@link RuntimeIngredientUpdates#SEARCH_LOCK}. A search
 * or a rebuild takes it only while it already holds {@code allElements}. A runtime insert takes it
 * alone, so it does not nest the other way.
 * <p>
 * {@link #getAllIngredients} returns a snapshot for the same reason: callers iterate the result
 * outside the lock.
 * <p>
 * <b>{@code add} is deliberately not overwritten.</b> Other add-ons inject into it - AllTheLeaks
 * patches this class's {@code add} to clear a retained reference - and overwriting the method removes
 * their injection point, which silently disables their patch. The backing map is replaced with a
 * synchronized wrapper in the constructor instead, so the {@code add} write takes the same monitor
 * as the lookups without touching the method body. See
 * {@link dev.jeasync.mixin.collect.IngredientSetMixin} for the same trade on the other class.
 * <p>
 * Targets upstream 15.74.0.234.
 */
@Mixin(value = ElementSearch.class, remap = false)
public abstract class ElementSearchMixin {
	private static final Logger JEI_ASYNC_LOGGER = LogManager.getLogger(ElementSearchMixin.class);

	private static final long JEI_ASYNC_TIMING_THRESHOLD_MILLIS = 25;

	@Shadow
	@Final
	@Mutable
	private Map<Object, IListElement<?>> allElements;

	@Shadow
	private PrefixInfo<IListElementInfo<?>, IListElement<?>> noPrefix;

	@Shadow
	private CombinedSearchables<IListElement<?>> combinedSearchables;

	@Shadow
	private Map<PrefixInfo<IListElementInfo<?>, IListElement<?>>, PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> prefixedSearchables;

	@Shadow
	private ElementPrefixParser elementPrefixParser;

	@Shadow
	private void updateCombinedSearchables() {
		throw new AssertionError();
	}

	@Shadow
	private void updateExistingIndexes(Collection<IListElementInfo<?>> infos, Set<ElementSearchIndex> indexesToRebuild) {
		throw new AssertionError();
	}

	/**
	 * Replaces the constructor's plain {@code HashMap} with a synchronized wrapper. Writes through
	 * {@code add} then share the monitor the lookups already use, without redirecting a call inside
	 * {@code add} (whose local slots are reused by the 0.233+ bytecode optimizer).
	 */
	@Redirect(
		method = "<init>",
		at = @At(
			value = "FIELD",
			target = "Lmezz/jei/gui/search/ElementSearch;allElements:Ljava/util/Map;",
			opcode = org.objectweb.asm.Opcodes.PUTFIELD
		)
	)
	private void jeasync$synchronizedAllElements(ElementSearch instance, Map<Object, IListElement<?>> map) {
		this.allElements = Collections.synchronizedMap(map);
	}

	/**
	 * Name search is built with the ingredient list. The other prefixes, tooltips especially,
	 * allocate their strings here, and that used to happen in the middle of startup while recipes
	 * and the GUI were still being built. A placeholder keeps the map entry without opening a
	 * Lucene index. The real strings are filled in after startup returns.
	 * <p>
	 * Overwritten rather than redirected because the 0.233+ bytecode optimizer reuses the local
	 * slots {@code this} and the arguments live in, which a redirect would reload.
	 *
	 * @author Mysticpasta1
	 * @reason Keep the deferral placeholder and route the build through the sharded builder.
	 */
	@Overwrite
	private void rebuildSearchIndex(ElementSearchIndex searchIndex, Collection<IListElementInfo<?>> infos) {
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo =
			((ElementPrefixParserInvoker) (Object) this.elementPrefixParser).jeasync$invokeGetPrefixInfo(searchIndex);
		if (prefixInfo == null) {
			return;
		}
		this.prefixedSearchables.put(prefixInfo, jeiAsyncBuildIndex(searchIndex, prefixInfo, infos));
	}

	/**
	 * The builder overload of {@code putIfNotBlank} fills a private tree during the initial index
	 * build and must stay unlocked. This one writes the live tree while plugins are still adding.
	 */
	@Redirect(
		method = "putIfNotBlank(Lmezz/jei/api/search/ISearchStorage;Ljava/lang/String;Ljava/lang/Object;)V",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/api/search/ISearchStorage;put(Ljava/lang/String;Ljava/lang/Object;)V"
		)
	)
	private static void jeasync$putSearch(ISearchStorage<Object> storage, String key, Object value) {
		synchronized (RuntimeIngredientUpdates.SEARCH_LOCK) {
			storage.put(key, value);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Build one prefix index with the ingredients divided over the worker pool.
	 */
	@Overwrite
	private static PrefixedSearchable<IListElementInfo<?>, IListElement<?>> createSearchable(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		Collection<IListElementInfo<?>> infos
	) {
		return SearchIndexBuild.create(prefixInfo, infos);
	}

	/**
	 * Builds one prefix, or the deferral placeholder for it.
	 * <p>
	 * While {@link JeiLoadProgress} is still deferring the heavy prefixes, everything except the
	 * name index keeps the empty storage: building it here would allocate the very strings the
	 * deferral is saving for after startup.
	 */
	private PrefixedSearchable<IListElementInfo<?>, IListElement<?>> jeiAsyncBuildIndex(
		ElementSearchIndex searchIndex,
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		Collection<IListElementInfo<?>> infos
	) {
		if (JeiLoadProgress.isDeferringHeavySearch() && searchIndex != ElementSearchIndex.NAMES_AND_ALIASES) {
			return new PrefixedSearchable<>(DeferredSearchStorage.get(), prefixInfo);
		}
		return SearchIndexBuild.create(prefixInfo, infos);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Build every requested prefix outside the locks, then install them under it.
	 */
	@Overwrite
	public void rebuildSearchIndexes(Set<ElementSearchIndex> searchIndexes, Collection<IListElementInfo<?>> infos) {
		long started = System.nanoTime();
		List<ElementSearchIndex> ordered = new ArrayList<>(searchIndexes);
		List<PrefixInfo<IListElementInfo<?>, IListElement<?>>> prefixInfos = new ArrayList<>(ordered.size());
		List<PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> built = new ArrayList<>(ordered.size());
		for (ElementSearchIndex searchIndex : ordered) {
			PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo =
				((ElementPrefixParserInvoker) (Object) this.elementPrefixParser).jeasync$invokeGetPrefixInfo(searchIndex);
			prefixInfos.add(prefixInfo);
			PrefixedSearchable<IListElementInfo<?>, IListElement<?>> searchable = null;
			if (prefixInfo != null) {
				try {
					searchable = jeiAsyncBuildIndex(searchIndex, prefixInfo, infos);
				} catch (Throwable t) {
					// One plugin's string getter must not take the other prefixes down with it.
					// The previous storage stays in place for this prefix.
					JEI_ASYNC_LOGGER.warn("jeasync: search index for prefix {} was not rebuilt", prefixInfo, t);
				}
			}
			built.add(searchable);
		}

		synchronized (allElements) {
			synchronized (RuntimeIngredientUpdates.SEARCH_LOCK) {
				for (int slot = 0; slot < ordered.size(); slot++) {
					PrefixedSearchable<IListElementInfo<?>, IListElement<?>> searchable = built.get(slot);
					if (searchable != null) {
						prefixedSearchables.put(prefixInfos.get(slot), searchable);
					}
				}
				updateCombinedSearchables();
			}
		}

		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		if (elapsed >= JEI_ASYNC_TIMING_THRESHOLD_MILLIS) {
			JEI_ASYNC_LOGGER.info(
				"Search indexes for {} prefixes built in {} ms over {} ingredients (ingredients divided across workers)",
				ordered.size(), elapsed, infos.size()
			);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock an incremental index update so it does not race a search.
	 */
	@Overwrite
	public void update(
		Collection<IListElementInfo<?>> updatedInfos,
		Set<ElementSearchIndex> rebuildingIndexes,
		Collection<IListElementInfo<?>> allInfos
	) {
		synchronized (allElements) {
			synchronized (RuntimeIngredientUpdates.SEARCH_LOCK) {
				Set<ElementSearchIndex> indexesToRebuild = EnumSet.noneOf(ElementSearchIndex.class);
				indexesToRebuild.addAll(rebuildingIndexes);
				if (!updatedInfos.isEmpty()) {
					updateExistingIndexes(updatedInfos, indexesToRebuild);
				}
				if (!indexesToRebuild.isEmpty()) {
					try {
						for (ElementSearchIndex searchIndex : indexesToRebuild) {
							rebuildSearchIndex(searchIndex, allInfos);
						}
					} finally {
						updateCombinedSearchables();
					}
				}
			}
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Read the search index under a lock so a query does not race a rebuild.
	 */
	@Overwrite
	public Set<IListElement<?>> getSearchResults(mezz.jei.gui.search.ElementPrefixParser.TokenInfo tokenInfo) {
		String token = tokenInfo.token();
		if (token.isEmpty()) {
			return Set.of();
		}

		synchronized (allElements) {
			synchronized (RuntimeIngredientUpdates.SEARCH_LOCK) {
				Set<IListElement<?>> results = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

				PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = tokenInfo.prefixInfo();
				if (prefixInfo == noPrefix) {
					combinedSearchables.getSearchResults(token, results::addAll);
					return results;
				}
				PrefixedSearchable<IListElementInfo<?>, IListElement<?>> searchable = prefixedSearchables.get(prefixInfo);
				if (searchable == null || searchable.getMode() == SearchMode.DISABLED) {
					combinedSearchables.getSearchResults(token, results::addAll);
					return results;
				}
				searchable.getSearchResults(token, results::addAll);
				return results;
			}
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the element map so a lookup does not race an add.
	 */
	@Overwrite
	@SuppressWarnings("unchecked")
	public <T> Optional<IListElement<T>> findElement(ITypedIngredient<T> ingredient, IIngredientHelper<T> ingredientHelper) {
		Object uniqueId = ingredientHelper.getUid(ingredient, UidContext.Ingredient);

		IListElement<?> element;
		synchronized (allElements) {
			element = allElements.get(uniqueId);
		}
		if (element != null && element.getTypedIngredient().getType().equals(ingredient.getType())) {
			return Optional.of((IListElement<T>) element);
		}
		return Optional.empty();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Snapshot the element map so the caller does not iterate it while it is being rebuilt.
	 */
	@Overwrite
	public Collection<IListElement<?>> getAllIngredients() {
		synchronized (allElements) {
			List<IListElement<?>> snapshot = new ArrayList<>(allElements.values());
			return snapshot;
		}
	}
}
