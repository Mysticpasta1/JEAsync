package dev.jeasync.mixin.local;

import mezz.jei.gui.recipes.RecipesGui;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RecipesGui.class, priority = 1, remap = false)
public interface RecipesGuiLocalSlotsMixin {
}
