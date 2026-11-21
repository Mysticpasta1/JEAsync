package dev.jeasync.mixin.optional;

import dev.jeasync.AsyncConfig;
import dev.jeasync.MixinApplied;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.registration.IRecipeRegistration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Stops Create's Item Drain category from building its recipe list.
 * <p>
 * {@code ItemDrainCategory.consumeRecipes} walks every item stack JEI knows, tests each one for a
 * fluid-handler item capability, drains it and builds an emptying recipe for the result. Filled
 * buckets, tanks, bottles and cells from every mod become one recipe each, and potion items become
 * one per potion, so a large pack both pays the scan and then holds the generated recipes together
 * with a JEI row and its focus links. The category's own list is the only place those recipes exist,
 * so cancelling its registration removes the whole cost.
 * <p>
 * The check names the category by recipe type id rather than by class, so it follows Create's
 * category however the plugin nests or renames it, and only {@code create:draining} is affected.
 * The category is still registered; with no recipes it hides itself, so no empty tab is left behind.
 * <p>
 * Create is optional: the mixin is pseudo, the injection is optional, and turning
 * {@code skipCreateItemDrainRecipes} off runs the method unchanged.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.compat.jei.category.CreateRecipeCategory", remap = false)
public abstract class CreateItemDrainCategoryMixin {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String ITEM_DRAIN_TYPE = "create:draining";

	@Shadow
	public abstract RecipeType<?> getRecipeType();

	@Inject(method = "registerRecipes", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void jeasync$skipItemDrainRecipes(IRecipeRegistration registration, CallbackInfo ci) {
		if (!AsyncConfig.skipCreateItemDrainRecipes()) {
			return;
		}
		RecipeType<?> recipeType = getRecipeType();
		if (recipeType == null || !ITEM_DRAIN_TYPE.equals(recipeType.getUid().toString())) {
			return;
		}
		if (!MixinApplied.isApplied("optional.CreateItemDrainCategoryMixin")) {
			MixinApplied.mark("optional.CreateItemDrainCategoryMixin");
			LOGGER.info(
				"jeasync: create's item drain recipes are not generated, the category has no recipes and hides itself"
			);
		}
		ci.cancel();
	}
}
