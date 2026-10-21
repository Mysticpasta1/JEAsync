package dev.jeiasync.mixin.collect;

import mezz.jei.api.recipe.RecipeType;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.library.recipes.RecipeManagerInternal;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Lets plugins register and hide recipes off the main thread.
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
 * {@code onRuntimeAvailable} calls {@code hideRecipes}, {@code unhideRecipes}, and the category
 * hide methods. Those callbacks run on a startup thread. {@code RecipeManagerInternal} guards the
 * hidden set and the category cache, so the main-thread assertion is omitted on that path too.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.recipes.RecipeManager.class, remap = false)
public abstract class RecipeManagerMixin {
	private static final Logger JEI_ASYNC_LOGGER = LogManager.getLogger();

	@Shadow
	private RecipeManagerInternal internal;

	/**
	 * @author Mysticpasta1
	 * @reason Drop the main-thread assertion so plugins can register recipes off the render thread.
	 * One recipe of the wrong class used to throw out of the whole plugin, which dropped every
	 * later category. Those entries are skipped and the rest are registered.
	 */
	@Overwrite
	public <T> void addRecipes(RecipeType<T> recipeType, List<T> recipes) {
		ErrorUtil.checkNotNull(recipeType, "recipeType");
		ErrorUtil.checkNotNull(recipes, "recipes");

		Class<?> recipeClass = recipeType.getRecipeClass();
		List<T> valid = new ArrayList<>(recipes.size());
		int skipped = 0;
		for (T recipe : recipes) {
			if (recipeClass.isInstance(recipe)) {
				valid.add(recipe);
			} else {
				skipped++;
			}
		}
		if (skipped > 0) {
			JEI_ASYNC_LOGGER.error(
				"JEI Async: skipped {} recipe(s) for {} that were not {}",
				skipped,
				recipeType,
				recipeClass.getName()
			);
		}

		// No ErrorUtil.assertMainThread() here: see the class comment.
		internal.addRecipes(recipeType, valid);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Drop the main-thread assertion so onRuntimeAvailable can hide recipes off the render thread.
	 */
	@Overwrite
	public <T> void hideRecipes(RecipeType<T> recipeType, Collection<T> recipes) {
		ErrorUtil.checkNotNull(recipes, "recipe");
		ErrorUtil.checkNotNull(recipeType, "recipeType");
		ErrorUtil.validateRecipes(recipeType, recipes);
		internal.hideRecipes(recipeType, recipes);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Drop the main-thread assertion so onRuntimeAvailable can unhide recipes off the render thread.
	 */
	@Overwrite
	public <T> void unhideRecipes(RecipeType<T> recipeType, Collection<T> recipes) {
		ErrorUtil.checkNotNull(recipes, "recipe");
		ErrorUtil.checkNotNull(recipeType, "recipeType");
		ErrorUtil.validateRecipes(recipeType, recipes);
		internal.unhideRecipes(recipeType, recipes);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Drop the main-thread assertion so onRuntimeAvailable can hide a category off the render thread.
	 */
	@Overwrite
	public void hideRecipeCategory(RecipeType<?> recipeType) {
		ErrorUtil.checkNotNull(recipeType, "recipeType");
		internal.hideRecipeCategory(recipeType);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Drop the main-thread assertion so onRuntimeAvailable can unhide a category off the render thread.
	 */
	@Overwrite
	public void unhideRecipeCategory(RecipeType<?> recipeType) {
		ErrorUtil.checkNotNull(recipeType, "recipeType");
		internal.unhideRecipeCategory(recipeType);
	}
}
