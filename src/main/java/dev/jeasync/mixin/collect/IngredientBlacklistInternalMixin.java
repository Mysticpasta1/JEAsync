package dev.jeasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.library.ingredients.IngredientVisibility;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Makes {@link mezz.jei.library.ingredients.IngredientBlacklistInternal} safe when ingredients are
 * mutated off-thread.
 * <p>
 * {@code removedIngredientUids} and {@code hiddenIngredientUids} are a {@code HashSet} and an
 * {@code EnumMap} of {@code HashSet}s. They are written by the listener callbacks below, which are
 * reached from {@code IngredientManager}'s runtime mutation path, and read by
 * {@code isIngredientBlacklistedByApi}, which runs on every visibility check including from the
 * render thread. This mod removes the main-thread assertion from those mutation paths, so both
 * sides can now run at once.
 * <p>
 * One monitor on {@code this} covers all of them. That is coarser than guarding each set, but
 * these are short operations, and a single lock removes the question of a set being mutated while
 * the enum map is iterated.
 * <p>
 * The visibility notification runs after that monitor is released. A listener asks this class
 * whether an ingredient is hidden, and the filter may already be holding its own monitor while it
 * asks. Notifying under this monitor would invert those two locks. Listeners also refresh the
 * ingredient grid, so one notification covers the whole batch instead of one call per ingredient.
 * <p>
 * The set updates follow upstream. Mixin cannot wrap those loops without replacing the methods,
 * and leaving them alone means unsynchronised access to the sets.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.ingredients.IngredientBlacklistInternal.class, remap = false)
public abstract class IngredientBlacklistInternalMixin implements IIngredientManager.IIngredientListener, IIngredientVisibility.IListener {
	@Shadow
	private IIngredientManager ingredientManager;

	@Shadow
	private Set<Object> removedIngredientUids;

	@Shadow
	private Map<UidContext, Set<Object>> hiddenIngredientUids;

	@Shadow
	private WeakReference<IngredientVisibility> ingredientVisibilityRef;

	@Shadow
	private <V> boolean isIngredientRemoved(ITypedIngredient<V> typedIngredient, IIngredientHelper<V> ingredientHelper) {
		throw new AssertionError();
	}

	@Shadow
	private <V> boolean isIngredientHidden(ITypedIngredient<V> typedIngredient, IIngredientHelper<V> ingredientHelper, UidContext context) {
		throw new AssertionError();
	}

	@Shadow
	private <T> void notifyListenersOfVisibilityChange(ITypedIngredient<T> ingredient, Collection<UidContext> contexts, boolean visible) {
		throw new AssertionError();
	}

	@Shadow
	private <V> Set<UidContext> updateHiddenContexts(ITypedIngredient<V> ingredient, IIngredientHelper<V> ingredientHelper, Collection<UidContext> contexts, boolean visible) {
		throw new AssertionError();
	}

	// Not @Override: this is a public method of the target class rather than one inherited from
	// either listener interface.
	public <V> boolean isIngredientBlacklistedByApi(ITypedIngredient<V> typedIngredient, IIngredientHelper<V> ingredientHelper, UidContext context) {
		synchronized (this) {
			if (isIngredientRemoved(typedIngredient, ingredientHelper)) {
				return true;
			}
			return isIngredientHidden(typedIngredient, ingredientHelper, context);
		}
	}

	@Override
	public <V> void onIngredientsAdded(IIngredientHelper<V> ingredientHelper, Collection<ITypedIngredient<V>> ingredients) {
		Map<Set<UidContext>, List<ITypedIngredient<V>>> groups = new HashMap<>();
		synchronized (this) {
			for (ITypedIngredient<V> ingredient : ingredients) {
				Object uid = ingredientHelper.getUid(ingredient, UidContext.Ingredient);
				if (removedIngredientUids.remove(uid)) {
					Set<UidContext> visibleContexts = EnumSet.allOf(UidContext.class);
					visibleContexts.removeIf(context -> isIngredientHidden(ingredient, ingredientHelper, context));
					jeiAsyncAddGroup(groups, visibleContexts, ingredient);
				}
			}
		}
		jeiAsyncNotifyVisibility(groups, true);
	}

	@Override
	public <V> void onIngredientsRemoved(IIngredientHelper<V> ingredientHelper, Collection<ITypedIngredient<V>> ingredients) {
		Map<Set<UidContext>, List<ITypedIngredient<V>>> groups = new HashMap<>();
		synchronized (this) {
			for (ITypedIngredient<V> ingredient : ingredients) {
				Object uid = ingredientHelper.getUid(ingredient, UidContext.Ingredient);
				if (removedIngredientUids.add(uid)) {
					Set<UidContext> changedContexts = EnumSet.allOf(UidContext.class);
					changedContexts.removeIf(context -> isIngredientHidden(ingredient, ingredientHelper, context));
					jeiAsyncAddGroup(groups, changedContexts, ingredient);
				}
			}
		}
		jeiAsyncNotifyVisibility(groups, false);
	}

	@Override
	public <V> void onIngredientVisibilityChanged(ITypedIngredient<V> ingredient, boolean visible) {
		onIngredientsVisibilityChanged(Set.of(ingredient), Set.of(UidContext.values()), visible);
	}

	@Override
	public <V> void onIngredientsVisibilityChanged(Collection<ITypedIngredient<V>> ingredients, Collection<UidContext> contexts, boolean visible) {
		Map<Set<UidContext>, List<ITypedIngredient<V>>> groups = new HashMap<>();
		synchronized (this) {
			for (ITypedIngredient<V> ingredient : ingredients) {
				IIngredientHelper<V> ingredientHelper = ingredientManager.getIngredientHelper(ingredient.getType());
				Set<UidContext> changedContexts = updateHiddenContexts(ingredient, ingredientHelper, contexts, visible);
				if (!isIngredientRemoved(ingredient, ingredientHelper) && !changedContexts.isEmpty()) {
					jeiAsyncAddGroup(groups, changedContexts, ingredient);
				}
			}
		}
		jeiAsyncNotifyVisibility(groups, visible);
	}

	private static <V> void jeiAsyncAddGroup(Map<Set<UidContext>, List<ITypedIngredient<V>>> groups, Set<UidContext> contexts, ITypedIngredient<V> ingredient) {
		if (contexts.isEmpty()) {
			return;
		}
		groups.computeIfAbsent(Set.copyOf(contexts), ignored -> new ArrayList<>()).add(ingredient);
	}

	/**
	 * One call per distinct set of uid contexts. The filter re-reads visibility, and the grid
	 * refreshes once for the batch instead of once per ingredient.
	 */
	private <V> void jeiAsyncNotifyVisibility(Map<Set<UidContext>, List<ITypedIngredient<V>>> groups, boolean visible) {
		if (groups.isEmpty() || ingredientVisibilityRef == null) {
			return;
		}
		IngredientVisibility visibility = ingredientVisibilityRef.get();
		if (visibility == null) {
			return;
		}
		for (Map.Entry<Set<UidContext>, List<ITypedIngredient<V>>> entry : groups.entrySet()) {
			visibility.notifyListeners(entry.getValue(), entry.getKey(), visible);
		}
	}
}