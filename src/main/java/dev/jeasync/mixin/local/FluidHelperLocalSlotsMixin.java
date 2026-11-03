package dev.jeasync.mixin.local;

import mezz.jei.forge.platform.FluidHelper;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = FluidHelper.class, priority = 1, remap = false)
public interface FluidHelperLocalSlotsMixin {
}
