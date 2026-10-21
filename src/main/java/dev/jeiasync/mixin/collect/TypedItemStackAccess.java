package dev.jeiasync.mixin.collect;

import mezz.jei.common.ingredients.itemStacks.TypedItemStack;
import net.minecraft.world.item.Item;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Reads the item held by a typed stack without building an {@link net.minecraft.world.item.ItemStack}.
 */
@Mixin(value = TypedItemStack.class, remap = false)
public interface TypedItemStackAccess {
	@Invoker("getItem")
	Item jeiasync$getItem();
}
