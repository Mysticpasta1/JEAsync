package dev.jeasync.mixin.local;

import mezz.jei.library.recipes.collect.RecipeMap;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = RecipeMap.class, priority = 1, remap = false)
public interface RecipeMapLocalSlotsMixin {
}
