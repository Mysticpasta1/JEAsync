package dev.jeiasync.mixin.collect;

import mezz.jei.api.recipe.RecipeType;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.library.recipes.RecipeManagerInternal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

/**
 * Lets opt-in plugins register recipes off the main thread.
 * <p>
 * {@link ErrorUtil#assertMainThread()} is called from {@code addRecipes}, and {@code addRecipes}
 * is what essentially every plugin calls during registration, so leaving that assertion in place
 * makes off-thread registration impossible: the first recipe a plugin adds throws, which is
 * exactly what this mod exists to avoid.
 * <p>
 * The assertion is dropped here rather than worked around because the whole write path underneath
 * is already made concurrent-safe by this mod's other mixins ({@code RecipeMap},
 * {@code RecipeTypeData}, {@code RecipeIngredientTable}, {@code IngredientToRecipesMap},
 * {@code RecipeManagerInternal}). Nothing is bounced to the main thread, so there is no deferred
 * work that could land after JEI has finished indexing.
 * <p>
 * The hide/unhide methods are deliberately left alone. They are user-driven GUI actions rather than
 * registration, and keeping the assertion there preserves upstream's diagnostics if another mod
 * calls them from the wrong thread.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.recipes.RecipeManager.class, remap = false)
public abstract class RecipeManagerMixin {
	@Shadow
	private RecipeManagerInternal internal;

	@Overwrite
	public <T> void addRecipes(RecipeType<T> recipeType, List<T> recipes) {
		ErrorUtil.checkNotNull(recipeType, "recipeType");
		ErrorUtil.checkNotNull(recipes, "recipes");
		ErrorUtil.validateRecipes(recipeType, recipes);

		// No ErrorUtil.assertMainThread() here: see the class comment.
		internal.addRecipes(recipeType, recipes);
	}
}