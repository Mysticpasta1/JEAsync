package dev.jeiasync.mixin.collect;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Makes {@link mezz.jei.library.recipes.collect.RecipeTypeData} safe for concurrent recipe
 * registration.
 * <p>
 * Both fields are {@code private final} and built inline, so a mixin cannot swap in concurrent
 * collections. Each is instead used under its own monitor, which is sufficient because each is a
 * single leaf collection.
 * <p>
 * Targets upstream 15.32.0.173. Later JEI releases add a {@code focusLinks} map here and need an
 * extra monitor.
 */
@Mixin(value = mezz.jei.library.recipes.collect.RecipeTypeData.class, remap = false)
public abstract class RecipeTypeDataMixin<T> {
	@Shadow
	private List<T> recipes;

	@Shadow
	private Set<T> hiddenRecipes;

	@Overwrite
	public List<T> getRecipes() {
		synchronized (recipes) {
			return Collections.unmodifiableList(recipes);
		}
	}

	@Overwrite
	public void addRecipes(Collection<T> recipes) {
		synchronized (this.recipes) {
			this.recipes.addAll(recipes);
		}
	}

	@Overwrite
	public Set<T> getHiddenRecipes() {
		synchronized (hiddenRecipes) {
			return hiddenRecipes;
		}
	}
}