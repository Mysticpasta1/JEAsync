package dev.jeiasync.mixin.nbt;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Writes a stack's tag the way {@link ItemStack#copy()} does, without {@code setTag}.
 * <p>
 * {@code setTag} writes a damage value and verifies the tag. Copy assigns the field.
 */
@Mixin(ItemStack.class)
public interface ItemStackTagAccess {
	@Accessor("tag")
	void jeiasync$setTag(CompoundTag tag);
}
