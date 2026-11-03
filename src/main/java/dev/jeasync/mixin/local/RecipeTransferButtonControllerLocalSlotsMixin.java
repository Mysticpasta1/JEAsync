package dev.jeasync.mixin.local;

import mezz.jei.gui.recipes.RecipeTransferButtonController;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RecipeTransferButtonController.class, priority = 1, remap = false)
public interface RecipeTransferButtonControllerLocalSlotsMixin {
}
