package dev.jeasync.mixin.collect;

import dev.jeasync.MixinApplied;
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
 * Targets upstream 15.74.0.227 and 15.74.0.228 (class unchanged between them).
 */
@Mixin(targets = "mezz.jei.common.ingredients.itemStacks.NormalizedTypedItemStack", remap = false)
public class NormalizedTypedItemStackMixin {
	@Unique
	private static volatile boolean jeasync$marked;

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
	private CompoundTag jeasync$keepCallerTag(CompoundTag tag) {
		jeasync$mark();
		return tag;
	}

	@Unique
	private static void jeasync$mark() {
		if (jeasync$marked) {
			return;
		}
		jeasync$marked = true;
		MixinApplied.mark("collect.NormalizedTypedItemStackMixin");
		org.apache.logging.log4j.LogManager.getLogger(NormalizedTypedItemStackMixin.class).info(
			"jeasync: recipe item wrappers hold the recipe stack's own compound"
		);
	}
}
