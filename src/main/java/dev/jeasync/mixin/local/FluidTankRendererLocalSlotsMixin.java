package dev.jeasync.mixin.local;

import mezz.jei.library.render.FluidTankRenderer;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(value = FluidTankRenderer.class, priority = 1, remap = false)
public interface FluidTankRendererLocalSlotsMixin {
}
