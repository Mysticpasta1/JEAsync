package dev.jeasync.mixin.optional;

import java.lang.reflect.Field;
import java.util.Arrays;

import dev.jeasync.AsyncConfig;
import dev.jeasync.MixinApplied;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Caps the display expansion of Mantle's potion ingredient.
 * <p>
 * A {@code PotionDisplayIngredient} is an item ingredient whose display list is every registered
 * potion applied to every one of its items. On the pack this was written for that is 561,001 stacks
 * behind a single Tinkers casting recipe, and the ingredient caches the whole array. The recipe
 * itself is only a "any potion-tipped arrow" cast; the expansion exists for the recipe viewer.
 * <p>
 * Actual matching is unaffected. {@code PotionDisplayIngredient} inherits
 * {@code ItemIngredient.test}, which checks {@code items.contains(stack.getItem())} or the tag and
 * never calls {@code getItems()}. This cap therefore changes only how many variants the viewer
 * draws and retains, not which recipes accept which stacks. Because showing fewer variants is a
 * display loss, the cap is opt-in: {@code maxPotionDisplayStacks} defaults to 0 (disabled), and a
 * positive value enables the memory saving.
 * <p>
 * The target and the method are matched loosely because Mantle is optional and its mapping names
 * have changed between versions. A missing class skips the mixin through {@link Pseudo}; a missing
 * method or the cache field still leaves the capped array for the current caller.
 */
@Pseudo
@Mixin(targets = "slimeknights.mantle.recipe.ingredient.PotionDisplayIngredient", remap = false)
public abstract class PotionDisplayIngredientMixin {
	@Unique
	private static volatile boolean jeasync$logged;

	@Inject(
		method = {"m_43908_", "getItems"},
		at = @At("RETURN"),
		cancellable = true,
		remap = false,
		require = 0
	)
	private void jeasync$capDisplayStacks(CallbackInfoReturnable<ItemStack[]> cir) {
		int cap = AsyncConfig.maxPotionDisplayStacks();
		ItemStack[] stacks = cir.getReturnValue();
		if (cap <= 0 || stacks == null || stacks.length <= cap) {
			return;
		}
		ItemStack[] capped = Arrays.copyOf(stacks, cap);
		jeasync$replaceCache(capped);
		cir.setReturnValue(capped);
		if (!jeasync$logged) {
			jeasync$logged = true;
			MixinApplied.mark("optional.PotionDisplayIngredientMixin");
			LogManager.getLogger().info(
				"jeasync: capped a potion display ingredient from {} to {} stacks; recipe matching still tests items directly",
				stacks.length,
				cap
			);
		}
	}

	/**
	 * Replace the ingredient's own cache with the capped array. Without this the target would keep
	 * the full array and rebuild it on every call, which is the retention this mixin exists to
	 * avoid. A different Mantle field shape is ignored rather than failing the load.
	 */
	@Unique
	private void jeasync$replaceCache(ItemStack[] capped) {
		try {
			Class<?> target = Class.forName(
				"slimeknights.mantle.recipe.ingredient.PotionDisplayIngredient",
				false,
				this.getClass().getClassLoader()
			);
			Field field = target.getDeclaredField("displayStacks");
			field.setAccessible(true);
			field.set(this, capped);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
			// The current caller still receives the capped array.
		}
	}
}
