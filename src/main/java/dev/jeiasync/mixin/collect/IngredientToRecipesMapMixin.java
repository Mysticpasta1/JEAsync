package dev.jeiasync.mixin.collect;

import org.jetbrains.annotations.UnmodifiableView;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Makes {@link mezz.jei.library.recipes.collect.IngredientToRecipesMap} safe for concurrent
 * recipe registration.
 * <p>
 * The uid map is a fastutil {@code Object2ObjectOpenHashMap} held in a final field, so the whole
 * map is used under its monitor and the {@link ArrayList} values it hands back are additionally
 * guarded, since those are handed to callers who iterate them.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.recipes.collect.IngredientToRecipesMap.class, remap = false)
public abstract class IngredientToRecipesMapMixin<R> {
	@Shadow
	private Map<Object, ArrayList<R>> uidToRecipes;

	@Overwrite
	public void add(R recipe, Collection<Object> ingredientUids) {
		synchronized (uidToRecipes) {
			for (Object uid : ingredientUids) {
				ArrayList<R> recipes = uidToRecipes.computeIfAbsent(uid, k -> new ArrayList<>());
				synchronized (recipes) {
					recipes.add(recipe);
				}
			}
		}
	}

	@Overwrite
	@UnmodifiableView
	public List<R> get(Object ingredientUid) {
		ArrayList<R> recipes;
		synchronized (uidToRecipes) {
			recipes = uidToRecipes.get(ingredientUid);
		}
		if (recipes == null) {
			return Collections.emptyList();
		}
		synchronized (recipes) {
			return Collections.unmodifiableList(recipes);
		}
	}

	@Overwrite
	public void compact() {
		synchronized (uidToRecipes) {
			uidToRecipes.values().forEach(recipes -> {
				synchronized (recipes) {
					recipes.trimToSize();
				}
			});
		}
	}
}