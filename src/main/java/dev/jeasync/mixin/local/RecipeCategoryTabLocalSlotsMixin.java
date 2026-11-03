package dev.jeasync.mixin.local;

import mezz.jei.gui.recipes.RecipeCategoryTab;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RecipeCategoryTab.class, priority = 1, remap = false)
public interface RecipeCategoryTabLocalSlotsMixin {
}
