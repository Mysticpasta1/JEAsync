package dev.jeiasync.mixin.search;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.search.ISearchStorage;
import mezz.jei.core.search.CombinedSearchables;
import mezz.jei.core.search.PrefixInfo;
import mezz.jei.core.search.PrefixedSearchable;
import mezz.jei.core.search.SearchMode;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import mezz.jei.gui.search.ElementSearch;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * Makes {@link ElementSearch}'s lookups safe while recipes are still being registered.
 * <p>
 * {@code allElements} and {@code prefixedSearchables} are plain {@code HashMap}s. JEI populates them
 * once during construction, but {@link #add} is called again for every ingredient added at runtime,
 * which can happen from a registration thread while the render thread is searching. The maps cannot
 * be swapped for concurrent ones because they are {@code final} fields, so every access is taken
 * under the map's own monitor instead.
 * <p>
 * {@link #getAllIngredients} returns a snapshot for the same reason: callers iterate the result
 * outside the lock, and a live view would throw {@code ConcurrentModificationException} the moment
 * an {@code add} landed. The build loop itself is unchanged, so this remains faithful to upstream
 * apart from the locking.
 * <p>
 * Targets upstream 15.32.0.173.
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
	private static <T> Object getUid(ITypedIngredient<T> ingredient, IIngredientManager ingredientManager) {
		throw new AssertionError();
	}

	private static void putIfNotBlank(ISearchStorage<IListElement<?>> storage, String string, IListElement<?> element) {
		String trimmed = string.trim();
		if (!trimmed.isEmpty()) {
			storage.put(trimmed, element);
		}
	}

	@Overwrite
	public <T> void add(IListElementInfo<T> info, IIngredientManager ingredientManager) {
		IListElement<T> element = info.getElement();
		Object uid = getUid(element.getTypedIngredient(), ingredientManager);
		synchronized (allElements) {
			allElements.put(uid, element);
		}
		for (PrefixedSearchable<IListElementInfo<?>, IListElement<?>> prefixedSearchable : prefixedSearchables.values()) {
			if (prefixedSearchable.getMode() == SearchMode.DISABLED) {
				continue;
			}
			ISearchStorage<IListElement<?>> storage = prefixedSearchable.getSearchStorage();
			for (String string : prefixedSearchable.getStrings(info)) {
				putIfNotBlank(storage, string, element);
			}
		}
	}

	@Overwrite
	@SuppressWarnings("unchecked")
	public <T> Optional<IListElement<T>> findElement(ITypedIngredient<T> ingredient, IIngredientHelper<T> ingredientHelper) {
		String uniqueId = ingredientHelper.getUniqueId(ingredient.getIngredient(), UidContext.Ingredient);
		IListElement<?> element;
		synchronized (allElements) {
			element = allElements.get(uniqueId);
		}
		return Optional.ofNullable((IListElement<T>) element);
	}

	@Overwrite
	public Collection<IListElement<?>> getAllIngredients() {
		synchronized (allElements) {
			return new ArrayList<>(allElements.values());
		}
	}
}