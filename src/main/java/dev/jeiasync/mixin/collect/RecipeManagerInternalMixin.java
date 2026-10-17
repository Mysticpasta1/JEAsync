package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.library.recipes.PluginManager;
import mezz.jei.library.recipes.collect.RecipeMap;
import mezz.jei.library.recipes.collect.RecipeTypeData;
import mezz.jei.library.recipes.collect.RecipeTypeDataMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Makes {@link mezz.jei.library.recipes.RecipeManagerInternal} safe for concurrent recipe
 * registration.
 * <p>
 * Two members need cross-thread guarantees:
 * <ul>
 *     <li>{@code hiddenRecipeTypes}, a plain {@code HashSet} in a final field. A mixin cannot
 *     replace a final field's contents, so its accesses are taken under a lock instead.</li>
 *     <li>{@code recipeCategoriesVisibleCache}. Upstream marks this {@code volatile}, but a mixin
 *     cannot add a modifier to an existing field, so its accesses are locked too. Locking gives
 *     the same happens-before guarantee that {@code volatile} would.</li>
 * </ul>
 * Every method that touches either one locks on {@code this}. Contention is negligible: these are
 * short reads and small-set updates.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.recipes.RecipeManagerInternal.class, remap = false)
public abstract class RecipeManagerInternalMixin {
	@Shadow
	private Set<RecipeType<?>> hiddenRecipeTypes;

	@Shadow
	private List<IRecipeCategory<?>> recipeCategoriesVisibleCache;

	@Shadow
	private RecipeTypeDataMap recipeTypeDataMap;

	@Shadow
	private EnumMap<RecipeIngredientRole, RecipeMap> recipeMaps;

	@Shadow
	private PluginManager pluginManager;

	@Shadow
	private IIngredientVisibility ingredientVisibility;

	@Shadow
	private Comparator<IRecipeCategory<?>> recipeCategoryComparator;

	@Shadow
	private List<IRecipeCategory<?>> recipeCategories;

	@Shadow
	private <T> Stream<T> getRecipesStream(RecipeType<T> recipeType, IFocusGroup focuses, boolean includeHidden) {
		throw new AssertionError();
	}

	@Shadow
	public <T> Stream<ITypedIngredient<?>> getRecipeCatalystStream(RecipeType<T> recipeType, boolean includeHidden) {
		throw new AssertionError();
	}

	@Shadow
	private <T> boolean addRecipe(IRecipeCategory<T> recipeCategory, T recipe, Set<T> hiddenRecipes) {
		throw new AssertionError();
	}

	// Deliberately not named getRecipeCategoriesUncached: a mixin method with the same name as the
	// target's own method replaces it, so the @Invoker below would resolve back to this shim and
	// recurse until the stack blew.
	private Stream<IRecipeCategory<?>> jeiAsyncUncachedCategories(Collection<IRecipeCategory<?>> recipeCategories, IFocusGroup focuses, boolean includeHidden) {
		return ((RecipeManagerInternalInvoker) (Object) this).jeiAsyncRecipeCategoriesUncached(recipeCategories, focuses, includeHidden);
	}

	@Overwrite
	public <T> void addRecipes(RecipeType<T> recipeType, List<T> recipes) {
		RecipeTypeData<T> recipeTypeData = recipeTypeDataMap.get(recipeType);
		IRecipeCategory<T> recipeCategory = recipeTypeData.getRecipeCategory();
		Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();

		List<T> addedRecipes = new ArrayList<>(recipes.size());
		for (T recipe : recipes) {
			if (addRecipe(recipeCategory, recipe, hiddenRecipes)) {
				addedRecipes.add(recipe);
			}
		}

		if (!addedRecipes.isEmpty()) {
			recipeTypeData.addRecipes(addedRecipes);
			synchronized (this) {
				recipeCategoriesVisibleCache = null;
			}
		}
	}

	@Overwrite
	public boolean isCategoryHidden(IRecipeCategory<?> recipeCategory, IFocusGroup focuses) {
		// hide the category if it has been explicitly hidden
		RecipeType<?> recipeType = recipeCategory.getRecipeType();
		synchronized (this) {
			if (hiddenRecipeTypes.contains(recipeType)) {
				return true;
			}
		}

		// hide the category if it has catalysts, but they have all been hidden
		if (getRecipeCatalystStream(recipeType, true).findAny().isPresent() &&
			getRecipeCatalystStream(recipeType, false).findAny().isEmpty())
		{
			return true;
		}

		// hide the category if it has no recipes, or if the recipes have all been hidden
		Stream<?> visibleRecipes = getRecipesStream(recipeType, focuses, false);
		return visibleRecipes.findAny().isEmpty();
	}

	@Overwrite
	private Stream<IRecipeCategory<?>> getRecipeCategoriesCached(Collection<IRecipeCategory<?>> recipeCategories, IFocusGroup focuses, boolean includeHidden) {
		if (recipeCategories.isEmpty() && focuses.isEmpty() && !includeHidden) {
			List<IRecipeCategory<?>> cached;
			synchronized (this) {
				cached = recipeCategoriesVisibleCache;
			}
			if (cached == null) {
				cached = jeiAsyncUncachedCategories(recipeCategories, focuses, includeHidden).toList();
				synchronized (this) {
					recipeCategoriesVisibleCache = cached;
				}
			}
			return cached.stream();
		}

		return jeiAsyncUncachedCategories(recipeCategories, focuses, includeHidden);
	}

	@Overwrite
	public <T> void hideRecipes(RecipeType<T> recipeType, Collection<T> recipes) {
		RecipeTypeData<T> recipeTypeData = recipeTypeDataMap.get(recipeType);
		Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();
		hiddenRecipes.addAll(recipes);
		synchronized (this) {
			recipeCategoriesVisibleCache = null;
		}
	}

	@Overwrite
	public <T> void unhideRecipes(RecipeType<T> recipeType, Collection<T> recipes) {
		RecipeTypeData<T> recipeTypeData = recipeTypeDataMap.get(recipeType);
		Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();
		hiddenRecipes.removeAll(recipes);
		synchronized (this) {
			recipeCategoriesVisibleCache = null;
		}
	}

	@Overwrite
	public void hideRecipeCategory(RecipeType<?> recipeType) {
		synchronized (this) {
			hiddenRecipeTypes.add(recipeType);
			recipeCategoriesVisibleCache = null;
		}
	}

	@Overwrite
	public void unhideRecipeCategory(RecipeType<?> recipeType) {
		recipeTypeDataMap.validate(recipeType);
		synchronized (this) {
			hiddenRecipeTypes.remove(recipeType);
			recipeCategoriesVisibleCache = null;
		}
	}
}