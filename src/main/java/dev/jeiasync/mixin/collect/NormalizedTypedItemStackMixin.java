package dev.jeiasync.mixin.collect;

import dev.jeiasync.MixinApplied;
import net.minecraft.nbt.CompoundTag;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps ingredient wrappers from pinning a second copy of the same compound.
 * <p>
 * The wrapper JEI builds for one registered recipe stack carries a deep copy of the tag instead
 * of the stack's own compound. Ten million wrappers carrying ten million copies were one of the
 * larger blocks of the registration heap. The wrapper only reads its tag, and the stacks the
 * scans give it share one compound per enchantment, so the wrapper can hold the caller's
 * compound by reference.
 * <p>
 * Targets upstream 15.74.0.227.
 */
@Mixin(targets = "mezz.jei.common.ingredients.itemStacks.NormalizedTypedItemStack", remap = false)
public class NormalizedTypedItemStackMixin {
	@Unique
	private static volatile boolean jeiasync$marked;

	/**
	 * @author Mysticpasta1
	 * @reason The wrapper copied the tag even when the caller's tag is the compound a scan shares for many stacks. Hold the caller's compound directly.
	 */
	@Redirect(
		method = "<init>(Lnet/minecraft/core/Holder;Lnet/minecraft/nbt/CompoundTag;)V",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/common/ingredients/itemStacks/NormalizedTypedItemStack;copyTag(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"
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
		MixinApplied.mark("collect.NormalizedTypedItemStackMixin");
		org.apache.logging.log4j.LogManager.getLogger(NormalizedTypedItemStackMixin.class).info(
			"JEI Async: recipe item wrappers hold the recipe stack's own compound"
		);
	}
}
