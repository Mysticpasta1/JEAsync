package dev.jeiasync.mixin.load;

import mezz.jei.common.util.ErrorUtil;
import org.spongepowered.asm.mixin.Mixin;

/**
 * Marks {@link ErrorUtil} so {@link dev.jeiasync.mixin.JeiAsyncMixinPlugin} can add the void
 * {@code checkNotNull(Object, String)} JEI 15.71 removed. The method cannot live here:
 * Mixin rejects a public static method on a mixin class.
 */
@Mixin(value = ErrorUtil.class, remap = false)
public abstract class ErrorUtilMixin {
}
