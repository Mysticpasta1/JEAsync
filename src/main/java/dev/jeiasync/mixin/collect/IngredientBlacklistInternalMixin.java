package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Set;

/**
 * Makes {@link mezz.jei.library.ingredients.IngredientBlacklistInternal} safe when ingredients are
 * mutated off-thread.
 * <p>
 * Its blacklist is a plain {@code HashSet}, written by {@code onIngredientsAdded} /
 * {@code onIngredientsRemoved} (reached from {@code IngredientManager}'s runtime mutation path) and
 * read by {@code isIngredientBlacklistedByApi}, which runs on every visibility check including
 * from the render thread.
 * <p>
 * The listener notification is deliberately made outside the monitor: it calls out into
 * {@code IngredientVisibility}, and holding the lock across that would risk a deadlock if a listener
 * re-entered the blacklist.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.ingredients.IngredientBlacklistInternal.class, remap = false)
public abstract class IngredientBlacklistInternalMixin {
	@Shadow
	private Set<String> uidBlacklist;

	@Shadow
	private <T> void notifyListenersOfVisibilityChange(ITypedIngredient<T> ingredient, boolean visible) {
		throw new AssertionError();
	}

	@Overwrite
	public <V> void addIngredientToBlacklist(ITypedIngredient<V> typedIngredient, IIngredientHelper<V> ingredientHelper) {
		V ingredient = typedIngredient.getIngredient();
		String uniqueName = ingredientHelper.getUniqueId(ingredient, mezz.jei.api.ingredients.subtypes.UidContext.Ingredient);

		boolean changed;
		synchronized (uidBlacklist) {
			changed = uidBlacklist.add(uniqueName);
		}
		if (changed) {
			notifyListenersOfVisibilityChange(typedIngredient, false);
		}
	}

	@Overwrite
	public <V> void removeIngredientFromBlacklist(ITypedIngredient<V> typedIngredient, IIngredientHelper<V> ingredientHelper) {
		V ingredient = typedIngredient.getIngredient();
		String uniqueName = ingredientHelper.getUniqueId(ingredient, mezz.jei.api.ingredients.subtypes.UidContext.Ingredient);

		boolean changed;
		synchronized (uidBlacklist) {
			changed = uidBlacklist.remove(uniqueName);
		}
		if (changed) {
			notifyListenersOfVisibilityChange(typedIngredient, true);
		}
	}

	@Overwrite
	public <V> boolean isIngredientBlacklistedByApi(ITypedIngredient<V> typedIngredient, IIngredientHelper<V> ingredientHelper) {
		V ingredient = typedIngredient.getIngredient();
		String uid = ingredientHelper.getUniqueId(ingredient, mezz.jei.api.ingredients.subtypes.UidContext.Ingredient);
		String uidWild = ingredientHelper.getWildcardId(ingredient);

		synchronized (uidBlacklist) {
			if (uid.equals(uidWild)) {
				return uidBlacklist.contains(uid);
			}
			return uidBlacklist.contains(uid) || uidBlacklist.contains(uidWild);
		}
	}
}