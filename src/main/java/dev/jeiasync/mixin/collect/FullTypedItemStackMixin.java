package dev.jeiasync.mixin.collect;

import dev.jeiasync.MixinApplied;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Counts an item stack without pinning a second copy of the same compound.
 * <p>
 * The wrapper for a stack whose count is not one carries a deep copy of the tag like the other
 * wrappers do. This wrap keeps the caller's compound by reference, and the normalized wrapper it
 * builds keeps the same reference.
 * <p>
 * Targets upstream 15.74.0.227.
 */
@Mixin(targets = "mezz.jei.common.ingredients.itemStacks.FullTypedItemStack", remap = false)
public class FullTypedItemStackMixin {
	@Unique
	private static volatile boolean jeiasync$marked;

	/**
	 * @author Mysticpasta1
	 * @reason The wrapper copied the tag even when the caller's tag is the compound a scan shares for many stacks. Hold the caller's compound directly.
	 */
	@Redirect(
		method = "<init>(Lnet/minecraft/core/Holder;Lnet/minecraft/nbt/CompoundTag;I)V",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/common/ingredients/itemStacks/FullTypedItemStack;copyTag(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"
		),
		remap = false
	)
	private CompoundTag jeiasync$keepCallerTag(CompoundTag tag) {
		jeiasync$mark();
		return tag;
	}

	@Unique
	private static void jeiasync$mark() {
		if (jeiasync$marked) {
			return;
		}
		jeiasync$marked = true;
		MixinApplied.mark("collect.FullTypedItemStackMixin");
		org.apache.logging.log4j.LogManager.getLogger(FullTypedItemStackMixin.class).info(
			"JEI Async: counted item wrappers hold the stack's own compound"
		);
	}
}
