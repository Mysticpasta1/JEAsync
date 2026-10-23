package dev.jeasync.mixin.optional;

import dev.jeasync.ClientRecipes;
import mezz.jei.api.registration.IRecipeRegistration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Re:Avaritia copies compressor, smithing, and extreme-crafting recipes out of the client recipe
 * manager. Put the synced list back immediately before that copy. Addon datapack recipes live in
 * the same manager, so they come back with it. Absent when Avaritia is not installed.
 */
@Pseudo
@Mixin(targets = "committee.nova.mods.avaritia.init.compat.jei.AvaritiaJeiPlugin", remap = false)
public abstract class AvaritiaJeiPluginMixin {
	@Inject(method = "registerRecipes", at = @At("HEAD"), remap = false, require = 0)
	private void jeasync$restoreSyncedRecipes(IRecipeRegistration registration, CallbackInfo ci) {
		ClientRecipes.restoreIfDrifted();
	}
}
