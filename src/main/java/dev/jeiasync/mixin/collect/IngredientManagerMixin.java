package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.runtime.IIngredientManager.IIngredientListener;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.library.ingredients.IngredientInfo;
import mezz.jei.library.ingredients.RegisteredIngredients;
import mezz.jei.library.ingredients.TypedIngredient;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Lets opt-in plugins add and remove ingredients off the main thread.
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
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.ingredients.IngredientManager.class, remap = false)
public abstract class IngredientManagerMixin implements mezz.jei.api.runtime.IIngredientManager {
	private static final Logger LOGGER = LogManager.getLogger();

	@Shadow
	private RegisteredIngredients registeredIngredients;

	@Shadow
	private List<IIngredientListener> listeners;

	@Overwrite
	public <V> void addIngredientsAtRuntime(IIngredientType<V> ingredientType, Collection<V> ingredients) {
		ErrorUtil.checkNotNull(ingredientType, "ingredientType");
		ErrorUtil.checkNotEmpty(ingredients, "ingredients");

		IngredientInfo<V> ingredientInfo = this.registeredIngredients.getIngredientInfo(ingredientType);

		LOGGER.info("Ingredients are being added at runtime: {} {}", ingredients.size(), ingredientType.getIngredientClass().getName());

		IIngredientHelper<V> ingredientHelper = ingredientInfo.getIngredientHelper();
		Collection<V> validIngredients = ingredients.stream()
			.filter(i -> {
				if (!ingredientHelper.isValidIngredient(i)) {
					String errorInfo = ingredientHelper.getErrorInfo(i);
					LOGGER.error("Attempted to add an invalid Ingredient: {}", errorInfo);
					return false;
				}
				if (!ingredientHelper.isIngredientOnServer(i)) {
					String errorInfo = ingredientHelper.getErrorInfo(i);
					LOGGER.error("Attempted to add an Ingredient that is not on the server: {}", errorInfo);
					return false;
				}
				return true;
			})
			.toList();

		ingredientInfo.addIngredients(validIngredients);

		List<IIngredientListener> listenersSnapshot;
		synchronized (listeners) {
			listenersSnapshot = List.copyOf(listeners);
		}
		if (!listenersSnapshot.isEmpty()) {
			List<ITypedIngredient<V>> typedIngredients = validIngredients.stream()
				.map(i -> TypedIngredient.createUnvalidated(ingredientType, i))
				.toList();

			listenersSnapshot.forEach(listener -> listener.onIngredientsAdded(ingredientHelper, typedIngredients));
		}
	}

	@Overwrite
	public <V> void removeIngredientsAtRuntime(IIngredientType<V> ingredientType, Collection<V> ingredients) {
		ErrorUtil.checkNotNull(ingredientType, "ingredientType");
		ErrorUtil.checkNotEmpty(ingredients, "ingredients");

		IngredientInfo<V> ingredientInfo = this.registeredIngredients.getIngredientInfo(ingredientType);

		LOGGER.info("Ingredients are being removed at runtime: {} {}", ingredients.size(), ingredientType.getIngredientClass().getName());

		ingredientInfo.removeIngredients(ingredients);

		List<IIngredientListener> listenersSnapshot;
		synchronized (listeners) {
			listenersSnapshot = List.copyOf(listeners);
		}
		if (!listenersSnapshot.isEmpty()) {
			List<ITypedIngredient<V>> typedIngredients = ingredients.stream()
				.flatMap(i -> TypedIngredient.createAndFilterInvalid(this, ingredientType, i, false).stream())
				.toList();

			IIngredientHelper<V> ingredientHelper = ingredientInfo.getIngredientHelper();

			listenersSnapshot.forEach(listener -> listener.onIngredientsRemoved(ingredientHelper, typedIngredients));
		}
	}

	@Overwrite
	public void registerIngredientListener(IIngredientListener listener) {
		ErrorUtil.checkNotNull(listener, "listener");
		synchronized (listeners) {
			this.listeners.add(listener);
		}
	}

	@Overwrite
	public void onRuntimeStopped() {
		synchronized (listeners) {
			this.listeners.clear();
		}
	}
}