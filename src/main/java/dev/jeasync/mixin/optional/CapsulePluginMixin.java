package dev.jeasync.mixin.optional;

import mezz.jei.api.registration.IRecipeRegistration;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Capsule's upgrade item pair stays null when that item is not registered.
 * Skip the upgrade recipes so the rest of the plugin still runs.
 */
@Pseudo
@Mixin(targets = "capsule.plugins.jei.CapsulePlugin", remap = false)
public abstract class CapsulePluginMixin {
	@Inject(method = "registerRecipes", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void jeasync$skipMissingUpgrade(IRecipeRegistration registration, CallbackInfo ci) {
		try {
			Class<?> items = Class.forName("capsule.items.CapsuleItems", false, CapsulePluginMixin.class.getClassLoader());
			if (items.getField("upgradedCapsule").get(null) == null) {
				LogManager.getLogger().info("jeasync: capsule has no upgrade item, so upgrade recipes are skipped");
				ci.cancel();
			}
		} catch (ReflectiveOperationException failure) {
			LogManager.getLogger().info("jeasync: capsule has no upgrade item, so upgrade recipes are skipped");
			ci.cancel();
		}
	}
}
