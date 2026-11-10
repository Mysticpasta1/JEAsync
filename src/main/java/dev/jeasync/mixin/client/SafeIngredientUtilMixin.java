package dev.jeasync.mixin.client;

import dev.jeasync.MixinApplied;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.common.util.SafeIngredientUtil;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Makes {@link SafeIngredientUtil}'s crash-recovery sets thread-safe.
 * <p>
 * Search index construction calls {@code getPlainTooltipForSearch} for every ingredient, and the
 * tooltip prefix now does that from several worker threads at once. The method reads
 * {@code CRASHING_INGREDIENT_TOOLTIPS} once per ingredient and adds to it when a renderer throws.
 * A plain {@code HashSet} corrupts under concurrent reads and writes, and the render thread reads
 * the same set while it draws tooltips.
 * <p>
 * The synchronized wrappers keep the original semantics exactly - equal ingredients, and a null
 * ingredient is tolerated - because the build path feeds whatever a plugin's ingredient helper
 * returns. They are uncontended in the normal case: the sets are only written by a failing
 * renderer.
 * <p>
 * The other two sets are replaced as well so every access through this class has the same rules.
 * <p>
 * Targets upstream 15.74.0.234.
 */
@Mixin(value = SafeIngredientUtil.class, remap = false)
public abstract class SafeIngredientUtilMixin {
	@Shadow
	@Final
	@Mutable
	private static Set<IIngredientRenderer<?>> CRASHING_INGREDIENT_BATCH_RENDERERS;

	@Shadow
	@Final
	@Mutable
	private static Set<Object> CRASHING_INGREDIENT_RENDERERS;

	@Shadow
	@Final
	@Mutable
	private static Set<Object> CRASHING_INGREDIENT_TOOLTIPS;

	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void jeasync$threadSafeCrashSets(CallbackInfo ci) {
		MixinApplied.mark("client.SafeIngredientUtilMixin");
		CRASHING_INGREDIENT_BATCH_RENDERERS = Collections.synchronizedSet(new HashSet<>());
		CRASHING_INGREDIENT_RENDERERS = Collections.synchronizedSet(new HashSet<>());
		CRASHING_INGREDIENT_TOOLTIPS = Collections.synchronizedSet(new HashSet<>());
	}
}
