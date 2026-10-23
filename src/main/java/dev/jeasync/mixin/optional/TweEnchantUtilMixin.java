package dev.jeasync.mixin.optional;

import dev.jeasync.AnvilEnchantCache;
import dev.jeasync.MixinApplied;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Set;

/**
 * Serves a stored category set while JEI's anvil or grindstone scan is on this thread.
 * <p>
 * The stored set is a copy. Callers during the scan only read it, and a later change to the set
 * that was just built cannot rewrite the entry. Outside the scan the original method runs.
 */
@Pseudo
@Mixin(targets = "t.tinkerswithenchants.util.TWEEnchantUtil", remap = false)
public class TweEnchantUtilMixin {
	@Unique
	private static volatile boolean jeasync$marked;

	/**
	 * @author Mysticpasta1
	 * @reason Each anvil book level and each grindstone enchantment asked this again for the same tool. Return the set stored for that tool.
	 */
	@Inject(
		method = "getCategories(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Set;",
		at = @At("HEAD"),
		cancellable = true,
		require = 0
	)
	private static void jeasync$useCachedCategories(ItemStack stack, CallbackInfoReturnable<Set<EnchantmentCategory>> cir) {
		jeasync$mark();
		if (stack == null || !AnvilEnchantCache.active()) {
			return;
		}
		Set<EnchantmentCategory> cached = AnvilEnchantCache.get(stack);
		if (cached != null) {
			cir.setReturnValue(cached);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Keep an immutable copy of the categories for this stack. The anvil scan only reads the set.
	 */
	@Inject(
		method = "getCategories(Lnet/minecraft/world/item/ItemStack;)Ljava/util/Set;",
		at = @At("RETURN"),
		require = 0
	)
	private static void jeasync$storeCategories(ItemStack stack, CallbackInfoReturnable<Set<EnchantmentCategory>> cir) {
		if (stack == null || !AnvilEnchantCache.active()) {
			return;
		}
		Set<EnchantmentCategory> value = cir.getReturnValue();
		if (value == null) {
			return;
		}
		AnvilEnchantCache.put(stack, value.isEmpty() ? Set.of() : Set.copyOf(value));
	}

	@Unique
	private static void jeasync$mark() {
		if (jeasync$marked) {
			return;
		}
		jeasync$marked = true;
		MixinApplied.mark("optional.TweEnchantUtilMixin");
	}
}
