package dev.jeasync.mixin.search;

import dev.jeasync.DeferredSearchStorage;
import dev.jeasync.JeiLoadProgress;
import dev.jeasync.RuntimeIngredientUpdates;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.search.ISearchStorage;
import mezz.jei.api.search.ISearchStorageBuilder;
import mezz.jei.common.config.SearchMode;
import mezz.jei.common.search.CombinedSearchables;
import mezz.jei.common.search.PrefixInfo;
import mezz.jei.common.search.PrefixedSearchable;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementSearch;
import mezz.jei.gui.search.ElementPrefixParser;
import mezz.jei.gui.search.ElementSearchIndex;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

import dev.jeasync.AsyncConfig;
import dev.jeasync.AsyncPluginSupport;

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
 * The live suffix tree has its own monitor, {@link RuntimeIngredientUpdates#SEARCH_LOCK}. A search
 * or a rebuild takes it only while it already holds {@code allElements}. A runtime insert takes it
 * alone, so it does not nest the other way.
 * <p>
 * {@link #getAllIngredients} returns a snapshot for the same reason: callers iterate the result
 * outside the lock.
 * <p>
 * <b>{@code add} is deliberately not overwritten.</b> Other add-ons inject into it - AllTheLeaks
 * patches this class's {@code add} to clear a retained reference - and overwriting the method removes
 * their injection point, which silently disables their patch. The {@code allElements} write is
 * redirected onto the same monitor as the lookups. See
 * {@link dev.jeasync.mixin.collect.IngredientSetMixin} for the same trade on the other class.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = ElementSearch.class, remap = false)
public abstract class ElementSearchMixin {
	@Shadow
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
	private void rebuildSearchIndex(ElementSearchIndex searchIndex, Collection<IListElementInfo<?>> infos) {
		throw new AssertionError();
	}

	@Shadow
	private void updateCombinedSearchables() {
		throw new AssertionError();
	}

	@Shadow
	private void updateExistingIndexes(Collection<IListElementInfo<?>> infos, Set<ElementSearchIndex> indexesToRebuild) {
		throw new AssertionError();
	}

	// Mixin does not apply a field initialiser, so this is created on first use.
	private static volatile ThreadLocal<Boolean> jeiAsyncEmptyInfos;

	private static ThreadLocal<Boolean> jeiAsyncEmptyInfos() {
		ThreadLocal<Boolean> local = jeiAsyncEmptyInfos;
		if (local != null) {
			return local;
		}
		synchronized (ElementSearchMixin.class) {
			local = jeiAsyncEmptyInfos;
			if (local == null) {
				local = ThreadLocal.withInitial(() -> Boolean.FALSE);
				jeiAsyncEmptyInfos = local;
			}
			return local;
		}
	}

	/**
	 * Name search is built with the ingredient list. The other prefixes, tooltips especially,
	 * allocate their strings here, and that used to happen in the middle of startup while recipes
	 * and the GUI were still being built. A placeholder keeps the map entry without opening a
	 * Lucene index. The real strings are filled in after startup returns.
	 */
	@Inject(method = "rebuildSearchIndex", at = @At("HEAD"), remap = false)
	private void jeasync$markHeavyIndex(ElementSearchIndex searchIndex, Collection<IListElementInfo<?>> infos, CallbackInfo ci) {
		jeiAsyncEmptyInfos().set(
			JeiLoadProgress.isDeferringHeavySearch() && searchIndex != ElementSearchIndex.NAMES_AND_ALIASES
		);
	}

	@Inject(method = "rebuildSearchIndex", at = @At("RETURN"), remap = false)
	private void jeasync$clearHeavyIndex(ElementSearchIndex searchIndex, Collection<IListElementInfo<?>> infos, CallbackInfo ci) {
		ThreadLocal<Boolean> local = jeiAsyncEmptyInfos;
		if (local != null) {
			local.set(Boolean.FALSE);
		}
	}

	@Redirect(
		method = "rebuildSearchIndex",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/gui/search/ElementSearch;createSearchable(Lmezz/jei/common/search/PrefixInfo;Ljava/util/Collection;)Lmezz/jei/common/search/PrefixedSearchable;"
		)
	)
	private PrefixedSearchable<IListElementInfo<?>, IListElement<?>> jeasync$deferHeavyStrings(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		Collection<IListElementInfo<?>> infos
	) {
		ThreadLocal<Boolean> local = jeiAsyncEmptyInfos;
		if (local != null && Boolean.TRUE.equals(local.get())) {
			return new PrefixedSearchable<>(DeferredSearchStorage.get(), prefixInfo);
		}
		return createSearchable(prefixInfo, infos);
	}

	/**
	 * {@code add} itself stays in place so an injector aimed at its body still matches.
	 * The map write takes {@code allElements}, which is the monitor the lookups already use.
	 */
	@Redirect(
		method = "add",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
		)
	)
	private Object jeasync$putElement(Map<Object, IListElement<?>> map, Object uid, Object element) {
		synchronized (allElements) {
			return map.put(uid, (IListElement<?>) element);
		}
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
	 * Upstream builds one search index per prefix, in sequence, and each one walks the whole
	 * ingredient list. This keeps that shape but times each prefix, because the cost is very
	 * unevenly distributed and the distribution decides whether there is anything to parallelise.
	 * <p>
	 * Parallelising the per-element string computation was tried and is not worth it. It runs - the
	 * bundled benchmark spreads it over 10 to 16 threads - but the work is allocation-bound rather
	 * than CPU-bound: {@code mod_names} alone allocates several sets, a regex matcher and a
	 * lowercased copy per ingredient, and the resulting garbage collection does not scale. Measured
	 * on the bundled benchmark, {@code mod_names} went from 154 ms to 140 ms, against a run-to-run
	 * spread of more than a second. The {@code tooltips} prefix is the larger cost and is left
	 * alone anyway: its strings come from {@code IIngredientRenderer.getTooltip}, which is plugin
	 * code, and calling that from several threads at once is not something this add-on is prepared
	 * to assume is safe.
	 */
	/**
	 * @author Mysticpasta1
	 * @reason Build one prefix index without sharing a mutable builder across threads.
	 */
	@Overwrite
	private static PrefixedSearchable<IListElementInfo<?>, IListElement<?>> createSearchable(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		Collection<IListElementInfo<?>> infos
	) {
		SearchMode searchMode = prefixInfo.getMode();
		if (searchMode == SearchMode.DISABLED) {
			return new PrefixedSearchable<>(DeferredSearchStorage.get(), prefixInfo);
		}
		long started = System.nanoTime();
		ISearchStorageBuilder<IListElement<?>> storageBuilder = prefixInfo.createStorageBuilder();
		for (IListElementInfo<?> info : infos) {
			Collection<String> strings = prefixInfo.getStrings(info);
			IListElement<?> element = info.getElement();
			for (String string : strings) {
				String trimmed = string.trim();
				if (!trimmed.isEmpty()) {
					storageBuilder.put(trimmed, element);
				}
			}
		}
		ISearchStorage<IListElement<?>> searchStorage = storageBuilder.build();
		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		if (elapsed >= JEI_ASYNC_TIMING_THRESHOLD_MILLIS) {
			org.apache.logging.log4j.LogManager.getLogger(ElementSearchMixin.class)
				.info("Search index for prefix {} took {} ms over {} ingredients", prefixInfo, elapsed, infos.size());
		}
		return new PrefixedSearchable<>(searchStorage, prefixInfo);
	}

	private static final long JEI_ASYNC_TIMING_THRESHOLD_MILLIS = 25;

	/**
	 * @author Mysticpasta1
	 * @reason Lock the element map while rebuilding so a search does not observe a half-built index.
	 */
	@Overwrite
	public void rebuildSearchIndexes(Set<ElementSearchIndex> searchIndexes, Collection<IListElementInfo<?>> infos) {
		synchronized (allElements) {
			synchronized (RuntimeIngredientUpdates.SEARCH_LOCK) {
				try {
					jeiAsyncBuildIndexes(searchIndexes, infos);
				} finally {
					updateCombinedSearchables();
				}
			}
		}
	}

	/**
	 * Upstream builds one search index at a time, each one walking every element, and the
	 * post-startup fill asks for six heavy indexes at once. This builds them side by side when
	 * the pool has threads to spare: one index per worker, each writing only its own storage
	 * builder, and the {@code prefixedSearchables} entries land here in list order after all of
	 * them finish, under the same lock the lookups take. An index whose strings threw keeps its
	 * previous storage, so a plugin tooltip failure no longer takes the other prefixes down.
	 * <p>
	 * While {@link JeiLoadProgress} is still deferring the heavy prefixes this stays on the
	 * sequential path: the deferral marker is a {@link ThreadLocal} that the private
	 * {@code rebuildSearchIndex} reads, and running the extraction directly would build the very
	 * strings the deferral is saving for after startup.
	 */
	private void jeiAsyncBuildIndexes(Set<ElementSearchIndex> searchIndexes, Collection<IListElementInfo<?>> infos) {
		if (searchIndexes.size() <= 1 || AsyncConfig.getThreadCount() <= 1 || JeiLoadProgress.isDeferringHeavySearch()) {
			for (ElementSearchIndex searchIndex : searchIndexes) {
				rebuildSearchIndex(searchIndex, infos);
			}
			return;
		}
		long started = System.nanoTime();
		List<ElementSearchIndex> ordered = new ArrayList<>(searchIndexes);
		List<PrefixInfo<IListElementInfo<?>, IListElement<?>>> prefixInfos = new ArrayList<>(ordered.size());
		List<CompletableFuture<PrefixedSearchable<IListElementInfo<?>, IListElement<?>>>> builders = new ArrayList<>(ordered.size());
		for (ElementSearchIndex index : ordered) {
			PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo =
				((ElementPrefixParserInvoker) (Object) this.elementPrefixParser).jeasync$invokeGetPrefixInfo(index);
			if (prefixInfo == null) {
				rebuildSearchIndex(index, infos);
				prefixInfos.add(null);
				builders.add(null);
				continue;
			}
			prefixInfos.add(prefixInfo);
			CompletableFuture<PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> builder;
			if (prefixInfo.getMode() == SearchMode.DISABLED) {
				builders.add(null);
				prefixedSearchables.put(prefixInfo, createSearchable(prefixInfo, infos));
				continue;
			}
			builder = CompletableFuture.supplyAsync(() -> createSearchable(prefixInfo, infos), AsyncPluginSupport.getExecutor());
			builders.add(builder);
		}
		for (int slot = 0; slot < ordered.size(); slot++) {
			jeiAsyncInstall(prefixInfos.get(slot), builders.get(slot));
		}
		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		if (elapsed >= JEI_ASYNC_TIMING_THRESHOLD_MILLIS) {
			org.apache.logging.log4j.LogManager.getLogger(ElementSearchMixin.class)
				.info("Search indexes for {} prefixes built in {} ms over {} ingredients (parallel)",
					ordered.size(), elapsed, infos.size());
		}
	}

	private void jeiAsyncInstall(
		PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo,
		CompletableFuture<PrefixedSearchable<IListElementInfo<?>, IListElement<?>>> builder
	) {
		if (prefixInfo == null || builder == null) {
			return;
		}
		PrefixedSearchable<IListElementInfo<?>, IListElement<?>> searchable;
		try {
			searchable = builder.join();
		} catch (Throwable t) {
			Throwable cause = t.getCause() != null ? t.getCause() : t;
			org.apache.logging.log4j.LogManager.getLogger(ElementSearchMixin.class)
				.warn("jeasync: search index for prefix {} was not rebuilt", prefixInfo, cause);
			return;
		}
		prefixedSearchables.put(prefixInfo, searchable);
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
