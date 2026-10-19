package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager.IIngredientListener;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.library.ingredients.IngredientInfo;
import mezz.jei.library.ingredients.RegisteredIngredients;
import mezz.jei.common.ingredients.TypedIngredient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Lets plugins add and remove ingredients off the main thread.
 * <p>
 * Same reasoning as {@link RecipeManagerMixin}: the {@code ErrorUtil.assertMainThread()} calls here
 * sit on the runtime registration path and would make off-thread plugin registration fail on its
 * first ingredient. The backing {@link mezz.jei.library.ingredients.IngredientSet} and the
 * {@code ListMultiMap} aliases are both made concurrent-safe by this mod's other mixins.
 * <p>
 * The listener list is a plain {@code ArrayList} in a final field, so registration and notification
 * are serialised on it. Notification is copied to a list before iterating so that a listener which
 * registers another listener mid-callback cannot cause a concurrent modification.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.ingredients.IngredientManager.class, remap = false)
public abstract class IngredientManagerMixin implements mezz.jei.api.runtime.IIngredientManager {
	private static final Logger LOGGER = LogManager.getLogger();

	@Shadow
	private RegisteredIngredients registeredIngredients;

	@Shadow
	private List<IIngredientListener> listeners;

	/**
	 * @author Mysticpasta1
	 * @reason Drop the main-thread assertion so plugins can add ingredients off the render thread.
	 */
	@Overwrite
	public <V> void addIngredientsAtRuntime(IIngredientType<V> ingredientType, Collection<V> ingredients) {
		ErrorUtil.checkNotNull(ingredientType, "ingredientType");
		ErrorUtil.checkNotEmpty(ingredients, "ingredients");

		IngredientInfo<V> ingredientInfo = this.registeredIngredients.getIngredientInfo(ingredientType);
		IIngredientHelper<V> ingredientHelper = ingredientInfo.getIngredientHelper();

		long started = System.nanoTime();
		List<ITypedIngredient<V>> validTypedIngredients = new ArrayList<>(ingredients.size());
		for (V ingredient : ingredients) {
			if (!ingredientHelper.isIngredientOnServer(ingredient)) {
				LOGGER.warn("Attempted to add an Ingredient that is not on the server: {}", ingredientHelper.getErrorInfo(ingredient));
				continue;
			}
			ITypedIngredient<V> typedIngredient = TypedIngredient.createAndFilterInvalid(ingredientHelper, ingredientType, ingredient, false);
			if (typedIngredient == null) {
				LOGGER.warn("Attempted to add an invalid ingredient at runtime: {}", ingredientHelper.getErrorInfo(ingredient));
				continue;
			}
			validTypedIngredients.add(typedIngredient);
		}

		ingredientInfo.addIngredients(validTypedIngredients);

		List<IIngredientListener> listenersSnapshot;
		synchronized (listeners) {
			listenersSnapshot = List.copyOf(listeners);
		}
		if (!listenersSnapshot.isEmpty()) {
			listenersSnapshot.forEach(listener -> listener.onIngredientsAdded(ingredientHelper, validTypedIngredients));
		}
		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		LOGGER.info("Ingredients are being added at runtime: {} {} ({} ms)", ingredients.size(), ingredientType.getIngredientClass().getName(), elapsed);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Drop the main-thread assertion so plugins can remove ingredients off the render thread.
	 */
	@Overwrite
	public <V> void removeIngredientsAtRuntime(IIngredientType<V> ingredientType, Collection<V> ingredients) {
		ErrorUtil.checkNotNull(ingredientType, "ingredientType");
		ErrorUtil.checkNotEmpty(ingredients, "ingredients");

		IngredientInfo<V> ingredientInfo = this.registeredIngredients.getIngredientInfo(ingredientType);
		IIngredientHelper<V> ingredientHelper = ingredientInfo.getIngredientHelper();

		long started = System.nanoTime();
		List<ITypedIngredient<V>> typedIngredients = TypedIngredient.createAndFilterInvalidNonnullList(this, ingredientType, ingredients, false);

		ingredientInfo.removeIngredients(typedIngredients);

		List<IIngredientListener> listenersSnapshot;
		synchronized (listeners) {
			listenersSnapshot = List.copyOf(listeners);
		}
		if (!listenersSnapshot.isEmpty()) {
			listenersSnapshot.forEach(listener -> listener.onIngredientsRemoved(ingredientHelper, typedIngredients));
		}
		long elapsed = (System.nanoTime() - started) / 1_000_000L;
		LOGGER.info("Ingredients are being removed at runtime: {} {} ({} ms)", ingredients.size(), ingredientType.getIngredientClass().getName(), elapsed);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Synchronize listener registration with notifications from worker threads.
	 */
	@Overwrite
	public void registerIngredientListener(IIngredientListener listener) {
		ErrorUtil.checkNotNull(listener, "listener");
		synchronized (listeners) {
			this.listeners.add(listener);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Synchronize listener clearing with notifications still running on a worker.
	 */
	@Overwrite
	public void onRuntimeStopped() {
		synchronized (listeners) {
			this.listeners.clear();
		}
	}
}
