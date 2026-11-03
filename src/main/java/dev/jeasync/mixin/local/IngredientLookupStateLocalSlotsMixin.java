package dev.jeasync.mixin.local;

import mezz.jei.gui.recipes.lookups.IngredientLookupState;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = IngredientLookupState.class, priority = 1, remap = false)
public interface IngredientLookupStateLocalSlotsMixin {
}
