package dev.jeiasync.mixin.collect;

import mezz.jei.api.recipe.category.extensions.IRecipeCategoryExtension;
import mezz.jei.library.recipes.ExtendableRecipeCategoryHelper;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Makes extension registration atomic across plugins.
 * <p>
 * {@code addRecipeExtensionFactory} checks a set and then adds to it. Two plugins registering
 * the same recipe class both pass the check. The handler list and the identity cache are plain
 * collections too, so a lookup during registration walks them while another plugin appends.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = ExtendableRecipeCategoryHelper.class, remap = false)
public abstract class ExtendableRecipeCategoryHelperMixin<T, W extends IRecipeCategoryExtension> {
	@Shadow
	private List<ExtendableRecipeCategoryHelper.RecipeHandler<? extends T, ? extends W>> recipeHandlers;

	@Shadow
	private Set<Class<? extends T>> handledClasses;

	@Shadow
	private Map<T, @Nullable W> cache;

	@Shadow
	private Class<? extends T> expectedRecipeClass;

	@Shadow
	private <R extends T> Optional<ExtendableRecipeCategoryHelper.RecipeHandler<R, W>> getBestRecipeHandler(R recipe) {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Make the handled-class check and the handler list update atomic across plugins, and keep the first factory so a second plugin can finish registering.
	 */
	@Overwrite
	public <R extends T> void addRecipeExtensionFactory(Class<? extends R> recipeClass, @Nullable Predicate<R> extensionFilter, Function<R, ? extends W> recipeExtensionFactory) {
		dev.jeiasync.MixinApplied.mark("collect.ExtendableRecipeCategoryHelperMixin");
		synchronized (this) {
			if (!expectedRecipeClass.isAssignableFrom(recipeClass)) {
				throw new IllegalArgumentException("Recipe handlers must handle a specific class. Needed: " + expectedRecipeClass + " Got: " + recipeClass);
			}
			if (this.handledClasses.contains(recipeClass)) {
				LogManager.getLogger().warn("JEI Async: kept the existing recipe extension for {}", recipeClass.getName());
				return;
			}
			this.handledClasses.add(recipeClass);
			this.recipeHandlers.add(new ExtendableRecipeCategoryHelper.RecipeHandler<>(recipeClass, extensionFilter, recipeExtensionFactory));
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Fill the extension cache without racing a registration on another thread.
	 */
	@Overwrite
	public <R extends T> Optional<W> getOptionalRecipeExtension(R recipe) {
		ExtendableRecipeCategoryHelper.RecipeHandler<R, W> handler;
		synchronized (this) {
			if (cache.containsKey(recipe)) {
				return Optional.ofNullable(cache.get(recipe));
			}
			handler = getBestRecipeHandler(recipe).orElse(null);
		}
		Optional<W> result = handler == null ? Optional.empty() : Optional.ofNullable(handler.apply(recipe));
		synchronized (this) {
			if (cache.containsKey(recipe)) {
				return Optional.ofNullable(cache.get(recipe));
			}
			cache.put(recipe, result.orElse(null));
			return result;
		}
	}
}
