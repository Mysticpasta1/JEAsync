package dev.jeasync.mixin.collect;

import mezz.jei.library.ingredients.RecipeIngredientSupplier.FocusLink;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes {@link mezz.jei.library.recipes.collect.RecipeTypeData} safe for concurrent recipe
 * registration.
 * <p>
 * Three collections, all built inline into {@code private final} fields, so a mixin cannot swap
 * them for concurrent ones. Each is used under its own monitor, which is sufficient because each is
 * a single leaf collection and no method here reaches another.
 * <p>
 * {@code focusLinks} arrived in 15.71 along with the focus-link API and needs its own guard: it is
 * written once per added recipe, on whichever worker thread is registering.
 * <p>
 * {@link #getRecipes} returns a snapshot rather than upstream's {@code Collections.unmodifiableList}
 * view. A view shares its iterator with the live list, so a plugin still registering recipes would
 * make the render thread throw {@code ConcurrentModificationException}, and holding the monitor
 * cannot prevent that because the caller iterates after this returns.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.recipes.collect.RecipeTypeData.class, remap = false)
public abstract class RecipeTypeDataMixin<T> {
	@Shadow
	private Map<T, List<FocusLink>> focusLinks;

	@Shadow
	private List<T> recipes;

	@Shadow
	private Set<T> hiddenRecipes;

	@Unique
	private volatile Set<T> jeasync$hiddenView;

	/**
	 * @author Mysticpasta1
	 * @reason Copy the recipe list under a lock so a reader does not iterate a live collection.
	 */
	@Overwrite
	public List<T> getRecipes() {
		synchronized (recipes) {
			return List.copyOf(recipes);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the recipe-list append so workers can register recipes of one type together.
	 */
	@Overwrite
	public void addRecipes(Collection<T> recipes) {
		synchronized (this.recipes) {
			this.recipes.addAll(recipes);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the focus-link map so workers can record links for the same recipe type together.
	 */
	@Overwrite
	public void addFocusLinks(T recipe, List<FocusLink> links) {
		synchronized (focusLinks) {
			focusLinks.put(recipe, List.copyOf(links));
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the focus-link lookup so a read does not race an add.
	 */
	@Overwrite
	public @Nullable List<FocusLink> getFocusLinks(T recipe) {
		synchronized (focusLinks) {
			return focusLinks.get(recipe);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Hand out one synchronized view of the identity set so a runtime hide cannot corrupt it.
	 */
	@Overwrite
	public Set<T> getHiddenRecipes() {
		Set<T> view = jeasync$hiddenView;
		if (view != null) {
			return view;
		}
		synchronized (hiddenRecipes) {
			view = jeasync$hiddenView;
			if (view == null) {
				view = Collections.synchronizedSet(hiddenRecipes);
				jeasync$hiddenView = view;
			}
			return view;
		}
	}
}
