package dev.jeasync.mixin.optional;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import dev.jeasync.MixinApplied;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * Apotheosis' spawner JEI plugin sorts its recipes with a comparator that contradicts itself, and
 * TimSort throws once enough recipes are in the list.
 * <p>
 * The comparator is the one the mod writes: a recipe whose offhand input is {@link Ingredient#EMPTY}
 * sorts first, but a recipe that has one is reported as coming after the other one without looking at
 * the other one at all. So for any two recipes that both need an offhand item, {@code compare(a, b)}
 * and {@code compare(b, a)} are both 1. That is not an ordering, and {@code List.sort} answers with
 * {@code IllegalArgumentException: Comparison method violates its general contract!}.
 * <p>
 * The exception is size- and arrangement-dependent, which is why it looks intermittent: under 32
 * elements the sort is a plain binary insertion and nothing checks the contract, and above that
 * TimSort only trips when the arrangement it happens to build contradicts itself. A pack with a few
 * datapack spawner modifiers sits just over the threshold, so the plugin either registers or is
 * skipped depending on the run. Losing the plugin costs the whole spawner category.
 * <p>
 * The same intent is kept here and made consistent: recipes that need no offhand item first, the
 * rest after them, and {@code List.sort} being stable, whatever order those two groups already had
 * is left alone. A list this mod cannot describe is handed back to its own comparator unchanged.
 */
@Pseudo
@Mixin(targets = "dev.shadowsoffire.apotheosis.spawn.compat.SpawnerJEIPlugin", remap = false)
public abstract class SpawnerRecipeSortMixin {
	/**
	 * The call at {@code SpawnerJEIPlugin:36}. {@code java.util.List} is not a Minecraft class, so
	 * this anchor reads the same in a dev run and in a shipped jar.
	 */
	@Redirect(
		method = "registerRecipes",
		at = @At(value = "INVOKE", target = "Ljava/util/List;sort(Ljava/util/Comparator;)V"),
		remap = false,
		require = 0
	)
	// registerRecipes is an instance method, so Mixin requires this handler to be non-static:
	// the enclosing SpawnerJEIPlugin instance arrives as the redirect's first argument.
	private void jeasync$sortRecipesByOffhand(List<?> recipes, Comparator<Object> modComparator) {
		Method getter = recipes.isEmpty() ? null : jeasync$findOffhandInput(recipes.get(0).getClass());
		if (getter == null) {
			recipes.sort(modComparator);
			return;
		}
		Comparator<Object> byOffhandItem = (first, second) -> Integer.compare(
			jeasync$offhandRank(getter, first),
			jeasync$offhandRank(getter, second)
		);
		recipes.sort(byOffhandItem);
		if (!MixinApplied.isApplied("optional.SpawnerRecipeSortMixin")) {
			MixinApplied.mark("optional.SpawnerRecipeSortMixin");
			LogManager.getLogger().info(
				"jeasync: apotheosis sorts its spawner recipes with an ordering that contradicts itself, so the sort is done by offhand item instead"
			);
		}
	}

	@Unique
	private static Method jeasync$findOffhandInput(Class<?> recipeClass) {
		try {
			return recipeClass.getMethod("getOffhandInput");
		} catch (ReflectiveOperationException | RuntimeException | Error failure) {
			return null;
		}
	}

	@Unique
	private static int jeasync$offhandRank(Method offhandInput, Object recipe) {
		try {
			return offhandInput.invoke(recipe) == Ingredient.EMPTY ? 0 : 1;
		} catch (ReflectiveOperationException | RuntimeException | Error failure) {
			// A recipe whose offhand item cannot be read keeps the place it was given.
			return 0;
		}
	}
}
