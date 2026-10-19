package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import mezz.jei.api.runtime.IIngredientVisibility.IListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Makes {@link mezz.jei.library.ingredients.IngredientVisibility} safe when ingredients are
 * mutated off-thread.
 * <p>
 * Its listener list is a plain {@code ArrayList}: populated during startup, then iterated by
 * {@code notifyListeners}, which is reached from {@code IngredientManager}'s runtime mutation path.
 * Since this mod removes the main-thread assertion from those paths, that notification can now
 * arrive on a plugin worker thread while the render thread is iterating, or while another plugin is
 * registering a listener.
 * <p>
 * Registration and teardown synchronize on the list, and notification works from a snapshot so a
 * listener registering another from inside its callback cannot cause a concurrent modification.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.ingredients.IngredientVisibility.class, remap = false)
public abstract class IngredientVisibilityMixin {
	@Shadow
	private List<IListener> listeners;

	/**
	 * @author Mysticpasta1
	 * @reason Synchronize listener registration with notifications from worker threads.
	 */
	@Overwrite
	public void registerListener(IListener listener) {
		synchronized (listeners) {
			this.listeners.add(listener);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Copy the listener list under a lock so notification does not race registration.
	 */
	@Overwrite
	public <V> void notifyListeners(Collection<ITypedIngredient<V>> ingredients, boolean visible) {
		notifyListeners(ingredients, Set.of(UidContext.values()), visible);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Copy the listener list under a lock so a context-scoped notification does not race registration.
	 */
	@Overwrite
	public <V> void notifyListeners(Collection<ITypedIngredient<V>> ingredients, Collection<UidContext> contexts, boolean visible) {
		List<IListener> snapshot;
		synchronized (listeners) {
			snapshot = new ArrayList<>(listeners);
		}
		for (IListener listener : snapshot) {
			listener.onIngredientsVisibilityChanged(ingredients, contexts, visible);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Synchronize listener clearing with notifications still running on a worker.
	 */
	@Overwrite
	public void onRuntimeStopped() {
		synchronized (listeners) {
			listeners.clear();
		}
	}
}
