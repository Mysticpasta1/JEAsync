package dev.jeiasync.mixin.search;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.gui.ingredients.IListElement;
import mezz.jei.gui.ingredients.IListElementInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Makes {@link mezz.jei.gui.search.ElementSearchLowMem} safe when ingredients are added
 * off-thread.
 * <p>
 * Same reasoning as {@link ElementSearchMixin}: it backs onto a plain {@code ArrayList} that
 * {@code add} appends to, while {@code getAllIngredients} returns a live transformed view and the
 * search methods iterate the list directly. Once ingredient registration can run on a worker
 * thread, those are a concurrent-modification hazard against the render thread.
 * <p>
 * This is the low-memory search backend, selected when the client config enables it, so it needs
 * the same treatment as the default backend rather than being left as a latent failure.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.gui.search.ElementSearchLowMem.class, remap = false)
public abstract class ElementSearchLowMemMixin {
	@Shadow
	private List<IListElementInfo<?>> elementInfoList;

	@Shadow
	private mezz.jei.common.search.PrefixInfo<IListElementInfo<?>, IListElement<?>> noPrefix;

	@Shadow
	private static boolean matches(String word, mezz.jei.common.search.PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo, IListElementInfo<?> elementInfo) {
		throw new AssertionError();
	}

	private List<IListElementInfo<?>> jeiAsyncSnapshot() {
		synchronized (elementInfoList) {
			return new ArrayList<>(elementInfoList);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Read the low-memory element list under a lock so a search does not race a rebuild.
	 */
	@Overwrite
	public Set<IListElement<?>> getSearchResults(mezz.jei.gui.search.ElementPrefixParser.TokenInfo tokenInfo) {
		String token = tokenInfo.token();
		if (token.isEmpty()) {
			return Set.of();
		}

		mezz.jei.common.search.PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = tokenInfo.prefixInfo();
		List<IListElement<?>> results = new ArrayList<>();
		for (IListElementInfo<?> elementInfo : jeiAsyncSnapshot()) {
			if (matches(token, prefixInfo, elementInfo)) {
				results.add(elementInfo.getElement());
			}
		}
		return Set.copyOf(results);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the element-list append so workers can add ingredients together.
	 */
	@Overwrite
	public <T> void add(IListElementInfo<T> info, IIngredientManager ingredientManager) {
		synchronized (elementInfoList) {
			this.elementInfoList.add(info);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the element-list rebuild so it does not race a search on another thread.
	 */
	@Overwrite
	public void rebuildSearchIndexes(Set<mezz.jei.gui.search.ElementSearchIndex> searchIndexes, Collection<IListElementInfo<?>> elementInfos) {
		if (searchIndexes.contains(mezz.jei.gui.search.ElementSearchIndex.UNPREFIXED)) {
			synchronized (elementInfoList) {
				this.elementInfoList.clear();
				this.elementInfoList.addAll(elementInfos);
			}
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Snapshot the element list so the caller does not iterate it while it is being rebuilt.
	 */
	@Overwrite
	public List<IListElement<?>> getAllIngredients() {
		List<IListElement<?>> elements = new ArrayList<>();
		for (IListElementInfo<?> elementInfo : jeiAsyncSnapshot()) {
			elements.add(elementInfo.getElement());
		}
		return elements;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the element scan so a lookup does not race an add.
	 */
	@Overwrite
	public <T> Optional<IListElement<T>> findElement(ITypedIngredient<T> typedIngredient, IIngredientHelper<T> ingredientHelper) {
		T ingredient = typedIngredient.getIngredient();
		mezz.jei.api.ingredients.IIngredientType<T> type = typedIngredient.getType();
		java.util.function.Function<ITypedIngredient<T>, Object> uidFunction =
			(i) -> ingredientHelper.getUniqueId(i.getIngredient(), mezz.jei.api.ingredients.subtypes.UidContext.Ingredient);
		Object ingredientUid = uidFunction.apply(typedIngredient);
		String lowercaseDisplayName = mezz.jei.gui.ingredients.DisplayNameUtil.getLowercaseDisplayNameForSearch(ingredient, ingredientHelper);
		mezz.jei.gui.search.ElementPrefixParser.TokenInfo tokenInfo =
			new mezz.jei.gui.search.ElementPrefixParser.TokenInfo(lowercaseDisplayName, noPrefix);
		mezz.jei.common.search.PrefixInfo<IListElementInfo<?>, IListElement<?>> prefixInfo = tokenInfo.prefixInfo();

		for (IListElementInfo<?> elementInfo : jeiAsyncSnapshot()) {
			if (matches(lowercaseDisplayName, prefixInfo, elementInfo)) {
				IListElement<?> element = elementInfo.getElement();
				IListElement<T> match = jeiAsyncCheckForMatch(element, type, ingredientUid, uidFunction);
				if (match != null) {
					return Optional.of(match);
				}
			}
		}

		return Optional.empty();
	}

	private <T> IListElement<T> jeiAsyncCheckForMatch(IListElement<?> element, mezz.jei.api.ingredients.IIngredientType<T> ingredientType, Object uid, java.util.function.Function<ITypedIngredient<T>, Object> uidFunction) {
		@SuppressWarnings("unchecked")
		IListElement<T> cast = (IListElement<T>) element;
		if (cast.getTypedIngredient().getType() != ingredientType) {
			return null;
		}
		Object elementUid = uidFunction.apply(cast.getTypedIngredient());
		return uid.equals(elementUid) ? cast : null;
	}
}
