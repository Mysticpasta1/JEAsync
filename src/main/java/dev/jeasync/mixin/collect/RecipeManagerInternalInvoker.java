package dev.jeasync.mixin.collect;

import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.library.recipes.RecipeManagerInternal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import java.util.Collection;
import java.util.stream.Stream;

/**
 * Calls {@code RecipeManagerInternal}'s private uncached category lookup.
 * <p>
 * The cache-rebuild path needs to bypass its own cache exactly once, and that method is private.
 * A shadowed private method is not found reliably by Mixin, so an invoker is used instead. At
 * runtime Mixin adds this interface to the target, so the cast is only there to satisfy javac.
 */
@Mixin(value = RecipeManagerInternal.class, remap = false)
public interface RecipeManagerInternalInvoker {
	@Invoker("getRecipeCategoriesUncached")
	Stream<IRecipeCategory<?>> jeiAsyncRecipeCategoriesUncached(Collection<IRecipeCategory<?>> recipeCategories, IFocusGroup focuses, boolean includeHidden);
}