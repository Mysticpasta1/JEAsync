package dev.jeasync.mixin.client;

import mezz.jei.library.render.FluidTankRenderer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks {@link FluidTankRenderer} so {@link dev.jeasync.mixin.JeAsyncMixinPlugin} can run
 * {@link dev.jeasync.compat.FluidTankRenderFix} before other mixins inject into {@code render}.
 * JEI 15.74 stores the tank width in local 0. An {@code aload_0} after that fails verification.
 */
@Mixin(value = FluidTankRenderer.class, priority = 20000, remap = false)
public abstract class FluidTankRendererMixin {
}
