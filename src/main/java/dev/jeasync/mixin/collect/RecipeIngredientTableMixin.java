package dev.jeasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.recipe.RecipeType;
import org.jetbrains.annotations.UnmodifiableView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;

/**
 * Makes {@link mezz.jei.library.recipes.collect.RecipeIngredientTable} safe for concurrent recipe
 * registration.
 * <p>
 * The outer map is a plain {@code HashMap} in a final field, so its monitor is taken around the
 * {@code computeIfAbsent} that publishes a new
 * {@link mezz.jei.library.recipes.collect.IngredientToRecipesMap}. That makes entry creation atomic;
 * the published map guards its own contents.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.recipes.collect.RecipeIngredientTable.class, remap = false)
public abstract class RecipeIngredientTableMixin {
	@Shadow
	private Map<RecipeType<?>, mezz.jei.library.recipes.collect.IngredientToRecipesMap<?>> map;

	/**
	 * @author Mysticpasta1
	 * @reason Lock the per-type row so workers can add recipe links together.
	 */
	@Overwrite
	public <V> void addExact(V recipe, RecipeType<V> recipeType, IIngredientType<?> ingredientType, Object uid) {
		mezz.jei.library.recipes.collect.IngredientToRecipesMap<V> ingredientToRecipesMap;
		synchronized (map) {
			@SuppressWarnings("unchecked")
			mezz.jei.library.recipes.collect.IngredientToRecipesMap<V> existing =
				(mezz.jei.library.recipes.collect.IngredientToRecipesMap<V>)
					this.map.computeIfAbsent(recipeType, k -> new mezz.jei.library.recipes.collect.IngredientToRecipesMap<>());
			ingredientToRecipesMap = existing;
		}
		ingredientToRecipesMap.addExact(recipe, ingredientType, uid);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the row lookup and return a stable list to the caller.
	 */
	@Overwrite
	@UnmodifiableView
	public <V> List<V> get(RecipeType<V> recipeType, IIngredientType<?> ingredientType, Object uid) {
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
		// Snapshot of the recipes linked to this ingredient. The index packs those links into one buffer.
		return ingredientToRecipesMap.get(ingredientType, uid);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock compaction so it does not race a concurrent add.
	 */
	@Overwrite
	public void compact() {
		synchronized (map) {
			map.values().forEach(mezz.jei.library.recipes.collect.IngredientToRecipesMap::compact);
		}
	}
}
