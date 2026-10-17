package dev.jeiasync.mixin.collect;

import com.google.common.collect.Multimap;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.library.ingredients.IIngredientSupplier;
import mezz.jei.library.recipes.collect.RecipeIngredientTable;
import org.jetbrains.annotations.UnmodifiableView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Makes {@link mezz.jei.library.recipes.collect.RecipeMap}'s access paths safe for concurrent
 * recipe registration.
 * <p>
 * The two uid-to-category indexes are unsynchronized Guava {@code SetMultimap}s in final fields,
 * so a mixin cannot swap them for {@code Multimaps.synchronizedSetMultimap}. Each is instead
 * guarded by its own monitor. This has to cover reads too, not just writes: a {@code put} that
 * creates a key can race with a concurrent {@code get} iterating that key's set.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.recipes.collect.RecipeMap.class, remap = false)
public abstract class RecipeMapMixin {
	@Shadow
	private RecipeIngredientTable recipeTable;

	@Shadow
	private Multimap<Object, RecipeType<?>> ingredientUidToCategoryMap;

	@Shadow
	private Multimap<Object, RecipeType<?>> categoryCatalystUidToRecipeCategoryMap;

	@Shadow
	private Comparator<RecipeType<?>> recipeTypeComparator;

	@Shadow
	private RecipeIngredientRole role;

	@Shadow
	private <T> Object getIngredientUid(ITypedIngredient<T> typedIngredient) {
		throw new AssertionError();
	}

	@Overwrite
	public <T> Stream<RecipeType<?>> getRecipeTypes(ITypedIngredient<T> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		Collection<RecipeType<?>> recipeCategoryUids;
		Collection<RecipeType<?>> catalystRecipeCategoryUids;
		synchronized (ingredientUidToCategoryMap) {
			recipeCategoryUids = List.copyOf(ingredientUidToCategoryMap.get(ingredientUid));
		}
		synchronized (categoryCatalystUidToRecipeCategoryMap) {
			catalystRecipeCategoryUids = List.copyOf(categoryCatalystUidToRecipeCategoryMap.get(ingredientUid));
		}
		return Stream.concat(recipeCategoryUids.stream(), catalystRecipeCategoryUids.stream())
			.sorted(recipeTypeComparator);
	}

	@Overwrite
	public <T> void addCatalystForCategory(RecipeType<?> recipeType, ITypedIngredient<T> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		synchronized (categoryCatalystUidToRecipeCategoryMap) {
			categoryCatalystUidToRecipeCategoryMap.put(ingredientUid, recipeType);
		}
	}

	@Overwrite
	@UnmodifiableView
	public <T> List<T> getRecipes(RecipeType<T> recipeType, ITypedIngredient<?> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		return recipeTable.get(recipeType, ingredientUid);
	}

	@Overwrite
	public <T> boolean isCatalystForRecipeCategory(RecipeType<T> recipeType, ITypedIngredient<?> ingredient) {
		Object ingredientUid = getIngredientUid(ingredient);
		synchronized (categoryCatalystUidToRecipeCategoryMap) {
			return categoryCatalystUidToRecipeCategoryMap.get(ingredientUid).contains(recipeType);
		}
	}

	@Overwrite
	public <T> void addRecipe(RecipeType<T> recipeType, T recipe, IIngredientSupplier ingredientSupplier) {
		Set<Object> ingredientUids = new HashSet<>();
		Collection<ITypedIngredient<?>> ingredients = ingredientSupplier.getIngredients(this.role);
		for (ITypedIngredient<?> ingredient : ingredients) {
			ingredientUids.add(getIngredientUid(ingredient));
		}

		if (!ingredientUids.isEmpty()) {
			synchronized (ingredientUidToCategoryMap) {
				for (Object ingredientUid : ingredientUids) {
					ingredientUidToCategoryMap.put(ingredientUid, recipeType);
				}
			}
			recipeTable.add(recipe, recipeType, ingredientUids);
		}
	}

	@Overwrite
	public void compact() {
		recipeTable.compact();
	}
}