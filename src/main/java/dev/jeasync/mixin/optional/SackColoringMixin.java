package dev.jeasync.mixin.optional;

import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Supplementaries builds a sack dyeing recipe for every dye. A color with no item used to abort
 * every Supplementaries recipe. That color is skipped by using an empty result stack.
 */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.supplementaries.common.items.crafting.SpecialRecipeDisplays", remap = false)
public abstract class SackColoringMixin {
	@Redirect(
		method = "makeSackColoringRecipes",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/Item;m_7968_()Lnet/minecraft/world/item/ItemStack;"),
		remap = false,
		require = 0
	)
	private static ItemStack jeasync$itemOrEmpty(Item item) {
		if (item == null) {
			return ItemStack.EMPTY;
		}
		return item.getDefaultInstance();
	}
}
