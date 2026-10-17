package dev.jeiasync.mixin.collect;

import mezz.jei.api.recipe.RecipeType;
import org.jetbrains.annotations.UnmodifiableView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Makes {@link mezz.jei.library.recipes.collect.RecipeIngredientTable} safe for concurrent recipe
 * registration.
 * <p>
 * The outer map is a plain {@code HashMap} in a final field, so its monitor is taken around the
 * {@code computeIfAbsent} that publishes a new {@link
 * mezz.jei.library.recipes.collect.IngredientToRecipesMap}. That makes entry creation atomic; the
 * published map guards its own contents.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.recipes.collect.RecipeIngredientTable.class, remap = false)
public abstract class RecipeIngredientTableMixin {
	@Shadow
	private Map<RecipeType<?>, mezz.jei.library.recipes.collect.IngredientToRecipesMap<?>> map;

	@Overwrite
	public <V> void add(V recipe, RecipeType<V> recipeType, Collection<Object> ingredientUids) {
		mezz.jei.library.recipes.collect.IngredientToRecipesMap<V> ingredientToRecipesMap;
		synchronized (map) {
			@SuppressWarnings("unchecked")
			mezz.jei.library.recipes.collect.IngredientToRecipesMap<V> existing =
				(mezz.jei.library.recipes.collect.IngredientToRecipesMap<V>)
					this.map.computeIfAbsent(recipeType, k -> new mezz.jei.library.recipes.collect.IngredientToRecipesMap<>());
			ingredientToRecipesMap = existing;
		}
		ingredientToRecipesMap.add(recipe, ingredientUids);
	}

	@Overwrite
	@UnmodifiableView
	public <V> List<V> get(RecipeType<V> recipeType, Object ingredientUid) {
		mezz.jei.library.recipes.collect.IngredientToRecipesMap<V> ingredientToRecipesMap;
		synchronized (map) {
			@SuppressWarnings("unchecked")
			mezz.jei.library.recipes.collect.IngredientToRecipesMap<V> existing =
				(mezz.jei.library.recipes.collect.IngredientToRecipesMap<V>) this.map.get(recipeType);
			ingredientToRecipesMap = existing;
		}
		if (ingredientToRecipesMap == null) {
			return List.of();
		}
		return ingredientToRecipesMap.get(ingredientUid);
	}

	@Overwrite
	public void compact() {
		synchronized (map) {
			map.values().forEach(mezz.jei.library.recipes.collect.IngredientToRecipesMap::compact);
		}
	}
}