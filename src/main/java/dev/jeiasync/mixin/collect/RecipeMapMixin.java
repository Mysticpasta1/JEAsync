package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.recipes.collect.RecipeIngredientTable;
import org.jetbrains.annotations.UnmodifiableView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
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
 * Targets upstream 19.69.0.456.
 */
@Mixin(value = mezz.jei.library.recipes.collect.RecipeMap.class, remap = false)
public abstract class RecipeMapMixin {
	@Shadow
	private RecipeIngredientTable recipeTable;

	@Shadow
	private <T, I> List<T> getRecipes(RecipeType<T> recipeType, IIngredientType<I> ingredientType, ITypedIngredient<?> ingredient) {
		throw new AssertionError();
	}

	@Inject(method = "getRecipeTypes", at = @At("RETURN"))
	private void jeiAsyncMaterializeGetRecipeTypes(CallbackInfoReturnable<Stream<RecipeType<?>>> cir) {
		// Drain the lazy stream here, while there is still a defined moment to do it.
		cir.setReturnValue(cir.getReturnValue().toList().stream());
	}

	/**
	 * @author Mysticpasta1
	 * @reason Return a copy so a reader does not iterate a list a worker is still filling.
	 */
	@Overwrite
	@UnmodifiableView
	public <T> List<T> getRecipes(RecipeType<T> recipeType, ITypedIngredient<?> ingredient) {
		List<T> recipes = getRecipes(recipeType, ingredient.getType(), ingredient);
		return recipes == null ? List.of() : List.copyOf(recipes);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Forward compaction to the table, which locks its rows while compacting.
	 */
	@Overwrite
	public void compact() {
		recipeTable.compact();
	}
}
