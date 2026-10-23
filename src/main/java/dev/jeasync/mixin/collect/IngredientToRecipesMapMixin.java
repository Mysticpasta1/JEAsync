package dev.jeasync.mixin.collect;

import dev.jeasync.CompactRecipeIndex;
import dev.jeasync.MixinApplied;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.library.recipes.collect.IngredientToRecipesMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;

/**
 * Stores ingredient-to-recipe links in {@link CompactRecipeIndex} instead of one {@code ArrayList}
 * of recipe objects per ingredient.
 * <p>
 * Upstream keeps every link as an object reference, and each ingredient owns its own list object
 * with spare slots. Registration of a large pack is dominated by those lists. The replacement
 * stores local recipe ids on the ingredient map. Compaction only drops spare slots. It does not
 * copy the ids into a second buffer.
 * <p>
 * {@link #get} returns a snapshot. The public lookup copies whatever this returns, so a live view
 * was copied immediately anyway.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(value = IngredientToRecipesMap.class, remap = false)
public abstract class IngredientToRecipesMapMixin<R> {
	@Unique
	private static volatile boolean jeasync$marked;

	@Unique
	private volatile CompactRecipeIndex<R> jeasync$index;

	@Unique
	private CompactRecipeIndex<R> jeasync$index() {
		CompactRecipeIndex<R> index = this.jeasync$index;
		if (index != null) {
			return index;
		}
		synchronized (this) {
			index = this.jeasync$index;
			if (index == null) {
				index = new CompactRecipeIndex<>();
				this.jeasync$index = index;
			}
			return index;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Record the recipe id instead of keeping another object reference per ingredient.
	 */
	@Overwrite
	public void addExact(R recipe, IIngredientType<?> ingredientType, Object uid) {
		if (!jeasync$marked) {
			jeasync$marked = true;
			MixinApplied.mark("collect.IngredientToRecipesMapMixin");
		}
		jeasync$index().add(ingredientType, uid, recipe);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Read the recipe ids back out of the ingredient map.
	 */
	@Overwrite
	public List<R> get(IIngredientType<?> ingredientType, Object uid) {
		CompactRecipeIndex<R> index = this.jeasync$index;
		if (index == null) {
			return List.of();
		}
		return index.get(ingredientType, uid);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Drop spare slots in the ingredient maps once registration has finished adding.
	 */
	@Overwrite
	public void compact() {
		CompactRecipeIndex<R> index = this.jeasync$index;
		if (index != null) {
			index.compact();
		}
	}
}
