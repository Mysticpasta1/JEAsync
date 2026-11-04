package dev.jeasync.mixin.optional;

import dev.jeasync.compat.XyCraftCaches;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Releases XyCraft's rule block-state index as soon as its JEI plugin has finished building it.
 * <p>
 * The plugin derives the item and fluid lists from that index during {@code registerRecipes}, then
 * never reads the index again. Waiting for the whole JEI load to finish would keep the map during
 * every later plugin and the deferred search build, so releasing at the return of this method
 * removes the peak instead of only the settled heap. The later post-load pass in
 * {@link XyCraftCaches} still covers recipe reloads and XyCraft versions whose plugin is not this
 * class.
 * <p>
 * XyCraft is optional; the mixin is pseudo, the injection is optional, and the release code is
 * reflective and guarded.
 */
@Pseudo
@Mixin(targets = "tv.soaryn.xycraft.machines.compat.jei.JeiPlugin", remap = false)
public abstract class XyCraftJeiPluginMixin {
	@Inject(method = "registerRecipes", at = @At("RETURN"), remap = false, require = 0)
	private void jeasync$releaseRuleCaches(CallbackInfo ci) {
		XyCraftCaches.release();
	}
}
