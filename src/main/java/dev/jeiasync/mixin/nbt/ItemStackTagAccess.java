package dev.jeiasync.mixin.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads and writes a stack's tag field directly.
 * <p>
 * {@code setTag} writes a damage value and verifies the tag. Copy assigns the field. The getter
 * observes the same field without a defensive copy.
 */
@Mixin(ItemStack.class)
public interface ItemStackTagAccess {
	@Accessor("tag")
	CompoundTag jeiasync$getTag();

	@Accessor("tag")
	void jeiasync$setTag(CompoundTag tag);
}
