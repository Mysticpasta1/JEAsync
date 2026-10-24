package dev.jeasync.mixin.optional;

import mezz.jei.api.runtime.IJeiRuntime;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Almost Fluidified hides duplicate fluid ingredients when JEI's runtime is ready. Its runtime
 * singleton is filled by a tag/recipe reload that has not necessarily run by the time JEI reaches
 * the {@code Sending Runtime} phase, so {@code getRuntime()} can still be {@code null} there. The
 * plugin then dereferences it and throws, which both fails it off-thread and again on the client
 * thread, and the fluid hide never lands.
 * <p>
 * Cancelling the callback while the singleton is absent keeps the failure from happening. JEI calls
 * the plugin again on the next runtime refresh, after the reload has filled the singleton, so the
 * fluids still end up hidden — this only drops the run that had nothing to hide yet.
 */
@Pseudo
@Mixin(targets = "com.shugabrush.almostfluidified.compat.AlmostFluidifiedJEI", remap = false)
public abstract class AlmostFluidifiedJeiMixin {
	@Inject(
		method = "onRuntimeAvailable",
		at = @At("HEAD"),
		cancellable = true,
		remap = false,
		require = 0
	)
	private void jeasync$skipWhenRuntimeMissing(IJeiRuntime runtime, CallbackInfo ci) {
		if (almostFluidifiedRuntime() == null) {
			LogManager.getLogger().info(
				"jeasync: almost fluidified has no runtime yet, so the fluid hide waits for a reload"
			);
			ci.cancel();
		}
	}

	private static Object almostFluidifiedRuntime() {
		try {
			Class<?> mod = Class.forName(
				"com.shugabrush.almostfluidified.AlmostFluidified",
				false,
				AlmostFluidifiedJeiMixin.class.getClassLoader()
			);
			return mod.getMethod("getRuntime").invoke(null);
		} catch (ReflectiveOperationException | LinkageError failure) {
			// The mod is absent or its shape changed: run the callback as JEI would have, unchanged.
			return new Object();
		}
	}
}
