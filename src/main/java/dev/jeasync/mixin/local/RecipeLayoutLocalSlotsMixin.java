package dev.jeasync.mixin.local;

import mezz.jei.library.gui.recipes.RecipeLayout;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RecipeLayout.class, priority = 1, remap = false)
public interface RecipeLayoutLocalSlotsMixin {
}
