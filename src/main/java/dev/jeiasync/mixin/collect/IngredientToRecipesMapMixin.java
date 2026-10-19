package dev.jeiasync.mixin.collect;

import dev.jeiasync.UnmodifiableListCache;
import mezz.jei.library.recipes.collect.IngredientToRecipesMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.Collections;
import java.util.List;

/**
 * Reuses the unmodifiable wrapper {@link IngredientToRecipesMap#get} hands back, instead of
 * allocating one per lookup.
 * <p>
 * {@code get} returns {@code Collections.unmodifiableList(recipesForIngredient)}. That wrapper is a
 * fresh object on every call, it is immutable, and it is a pure function of the list it wraps - so
 * two calls on the same underlying list produce two interchangeable objects. Reusing it removes the
 * per-lookup allocation without changing what the caller sees: the wrapper is still an unmodifiable
 * view over the same live {@code ArrayList}, so it still reflects recipes added after the lookup,
 * exactly as a freshly-built wrapper would.
 * <p>
 * {@link IngredientToRecipesMap#compact} calls {@code trimToSize} on the underlying lists, which
 * replaces the list's internal array but keeps the list object itself, so the cached wrapper stays
 * valid across a compaction.
 * <p>
 * This is a lookup-path saving, not a startup one: it does nothing for JEI's load time, which is
 * where this add-on's headline numbers come from. The allocation it removes is short-lived and dies
 * in the young generation, so the effect is a small reduction in scavenge pressure rather than in
 * the live set.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = IngredientToRecipesMap.class, remap = false)
public abstract class IngredientToRecipesMapMixin {
	@Unique
	private volatile UnmodifiableListCache jeiAsync$unmodifiableListCache;

	@Redirect(
		method = "get",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/Collections;unmodifiableList(Ljava/util/List;)Ljava/util/List;"
		)
	)
	private List<?> jeiAsyncCachedUnmodifiable(List<?> list) {
		UnmodifiableListCache cache = this.jeiAsync$unmodifiableListCache;
		if (cache == null) {
			cache = new UnmodifiableListCache();
			this.jeiAsync$unmodifiableListCache = cache;
		}
		return cache.get(list);
	}
}