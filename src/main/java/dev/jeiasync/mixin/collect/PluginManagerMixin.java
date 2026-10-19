package dev.jeiasync.mixin.collect;

import com.google.common.base.Stopwatch;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.advanced.IRecipeManagerPlugin;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.library.recipes.collect.RecipeTypeData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Makes {@link mezz.jei.library.recipes.PluginManager} safe for concurrent use.
 * <p>
 * Its {@code plugins} field is a plain {@code ArrayList} that is not even final: {@code addAll}
 * appends to it during registration while {@code safeCallPlugin}, which runs on the read path,
 * reassigns it when a recipe-registry plugin crashes. That is a lost-update race as well as a
 * chance of a {@link java.util.ConcurrentModificationException} mid-stream. All three of those
 * methods therefore take the instance monitor, and the readers work from a snapshot so that a long
 * stream is not held under the lock.
 * <p>
 * Also adds a fast path for the overwhelmingly common case of nothing being hidden: without it,
 * every recipe checked while browsing would take the {@code hiddenRecipes} monitor once per recipe
 * per frame.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.recipes.PluginManager.class, remap = false)
public abstract class PluginManagerMixin {
	private static final Logger LOGGER = LogManager.getLogger();

	@Shadow
	private List<IRecipeManagerPlugin> plugins;

	@Shadow
	private <T> Stream<T> getPluginRecipeStream(IRecipeManagerPlugin plugin, IRecipeCategory<T> recipeCategory, IFocusGroup focuses) {
		throw new AssertionError();
	}

	@Shadow
	private Stream<RecipeType<?>> getPluginRecipeTypeStream(IRecipeManagerPlugin plugin, IFocusGroup focuses) {
		throw new AssertionError();
	}

	private List<IRecipeManagerPlugin> jeiAsyncSnapshot() {
		synchronized (this) {
			return new ArrayList<>(this.plugins);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Snapshot the plugin list so a recipe query does not race plugin registration.
	 */
	@Overwrite
	public <T> Stream<T> getRecipes(RecipeTypeData<T> recipeTypeData, IFocusGroup focusGroup, boolean includeHidden) {
		IRecipeCategory<T> recipeCategory = recipeTypeData.getRecipeCategory();

		Stream<T> recipes = jeiAsyncSnapshot().stream()
			.flatMap(p -> getPluginRecipeStream(p, recipeCategory, focusGroup))
			.distinct();

		if (!includeHidden) {
			Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();
			if (!hiddenRecipes.isEmpty()) {
				Predicate<T> notHidden = ((Predicate<T>) hiddenRecipes::contains).negate();
				recipes = recipes.filter(notHidden);
			}
		}
		return recipes;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Snapshot the plugin list so a type query does not race plugin registration.
	 */
	@Overwrite
	public Stream<RecipeType<?>> getRecipeTypes(IFocusGroup focusGroup) {
		return jeiAsyncSnapshot().stream()
			.flatMap(p -> getPluginRecipeTypeStream(p, focusGroup))
			.distinct();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Keep a crashing recipe plugin from escaping the timed call and corrupting the shared list.
	 */
	@Overwrite
	private <T> T safeCallPlugin(IRecipeManagerPlugin plugin, Supplier<T> supplier, T defaultValue) {
		Stopwatch stopWatch = Stopwatch.createStarted();
		try {
			T result = supplier.get();
			stopWatch.stop();
			if (stopWatch.elapsed(TimeUnit.MILLISECONDS) > 10) {
				LOGGER.warn("Recipe registry plugin is slow, took {}. {}", stopWatch, plugin.getClass());
			}
			return result;
		} catch (RuntimeException | LinkageError e) {
			LOGGER.error("Recipe registry plugin crashed, it is being disabled: {}", plugin.getClass(), e);
			// Copy under the lock, so a concurrent addAll is not lost by the reassignment.
			synchronized (this) {
				List<IRecipeManagerPlugin> updated = new ArrayList<>(this.plugins);
				updated.remove(plugin);
				this.plugins = updated;
			}
			return defaultValue;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock plugin-list appends so concurrent registration does not lose updates.
	 */
	@Overwrite
	public <T> void addAll(List<IRecipeManagerPlugin> plugins) {
		synchronized (this) {
			this.plugins.addAll(plugins);
		}
	}
}
