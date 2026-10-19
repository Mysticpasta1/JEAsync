package dev.jeiasync.mixin.optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Tooltip search rolls the client world random from a JEI Async thread.
 * FastChunkGen logs a {@code ConcurrentModificationException} before {@code nextInt} uses its per-thread fallback.
 * Skipping the report on those threads leaves the fallback in place.
 */
@Pseudo
@Mixin(targets = "com.misanthropy.fastchunkgen.fixes.worldgen.threading_issues.common.CheckedThreadLocalRandom", remap = false)
public abstract class CheckedThreadLocalRandomMixin {
	@Inject(method = "handleNotOwner", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void jeiasync$skipReport(CallbackInfo ci) {
		if (Thread.currentThread().getName().startsWith("JEI Async ")) {
			ci.cancel();
		}
	}
}
