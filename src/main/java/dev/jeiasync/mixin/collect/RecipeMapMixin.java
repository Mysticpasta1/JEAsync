package dev.jeiasync.mixin.collect;

import dev.jeiasync.CompactRecipeIndex;
import dev.jeiasync.ItemSubtypeCache;
import dev.jeiasync.JeiLoadProgress;
import dev.jeiasync.MixinApplied;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.ingredients.itemStacks.TypedItemStack;
import mezz.jei.library.recipes.collect.RecipeIngredientTable;
import net.minecraft.world.item.Item;
import org.jetbrains.annotations.UnmodifiableView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.stream.Stream;

/**
 * Makes {@link mezz.jei.library.recipes.collect.RecipeMap} safe to read while recipes are being
 * registered.
 * <p>
 * Upstream hands out a live view of its recipe list, and a lazy stream over its recipe types. Neither
 * is safe for a reader that is not the writer: a view shares its iterator with the list it wraps, so a
 * reader iterating while a plugin registers throws {@code ConcurrentModificationException}, and a
 * stream is not touched at all until someone consumes it - long after the moment it would have been
 * safe to read. So {@link #getRecipes} returns a snapshot, and the {@code getRecipeTypes} handler
 * materialises its stream at the return point and hands back one over the copied list.
 * <p>
 * Nothing here writes. Callers that fill the map do so through the locked table methods.
 * <p>
 * While recipes are registering, a plain item is indexed by the item itself. Building an
 * {@code ItemStack} for that lookup copies nbt and, without {@link TypedItemStackMixin}, pins the
 * stack in a one-second cache. Items with a subtype interpreter still take the original path,
 * because their id depends on nbt.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(value = mezz.jei.library.recipes.collect.RecipeMap.class, remap = false)
public abstract class RecipeMapMixin {
	@Unique
	private static volatile boolean jeiasync$uidFastPathMarked;

	@Shadow
	private RecipeIngredientTable recipeTable;

	@Shadow
	private IIngredientManager ingredientManager;

	@Shadow
	private <T, I> List<T> getRecipes(RecipeType<T> recipeType, IIngredientType<I> ingredientType, ITypedIngredient<?> ingredient) {
		throw new AssertionError();
	}

	@Inject(method = "getRecipeTypes", at = @At("RETURN"), cancellable = true)
	private void jeiAsyncMaterializeGetRecipeTypes(CallbackInfoReturnable<Stream<RecipeType<?>>> cir) {
		// Drain the lazy stream here, while there is still a defined moment to do it.
		// Mysticpasta1 — setReturnValue without cancellable throws and JEI disables this plugin.
		Stream<RecipeType<?>> types = cir.getReturnValue();
		if (types != null) {
			cir.setReturnValue(types.toList().stream());
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Return a copy so a reader does not iterate a list a worker is still filling.
	 */
	@Overwrite
	@UnmodifiableView
	public <T> List<T> getRecipes(RecipeType<T> recipeType, ITypedIngredient<?> ingredient) {
		List<T> recipes = getRecipes(recipeType, ingredient.getType(), ingredient);
		if (recipes == null || recipes.isEmpty()) {
			return List.of();
		}
		// Already a private snapshot of recipe references. Copying it again doubles the lookup.
		if (recipes instanceof CompactRecipeIndex.Frozen) {
			return recipes;
		}
		return List.copyOf(recipes);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Forward compaction to the table, which locks its rows while compacting.
	 */
	@Overwrite
	public void compact() {
		recipeTable.compact();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Skip the item-stack allocation for ingredients whose recipe id is the item.
	 */
	@Overwrite
	private <T> Object getIngredientUid(ITypedIngredient<T> typedIngredient) {
		if (JeiLoadProgress.isLoading() && typedIngredient instanceof TypedItemStack typedItem) {
			Item item = ((TypedItemStackAccess) (Object) typedItem).jeiasync$getItem();
			if (!ItemSubtypeCache.hasSubtypes(this.ingredientManager, item)) {
				if (!jeiasync$uidFastPathMarked) {
					jeiasync$uidFastPathMarked = true;
					MixinApplied.mark("collect.RecipeMapMixin");
				}
				return item;
			}
		}
		IIngredientType<T> type = typedIngredient.getType();
		T ingredient = typedIngredient.getIngredient();
		IIngredientHelper<T> ingredientHelper = this.ingredientManager.getIngredientHelper(type);
		if (type instanceof IIngredientTypeWithSubtypes<?, T> ingredientTypeWithSubtypes) {
			if (!ingredientHelper.hasSubtypes(ingredient)) {
				return ingredientTypeWithSubtypes.getBase(ingredient);
			}
		}
		return ingredientHelper.getUid(ingredient, UidContext.Recipe);
	}
}
