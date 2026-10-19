package dev.jeiasync.mixin.collect;

import dev.jeiasync.MixinApplied;
import mezz.jei.api.recipe.category.extensions.vanilla.crafting.ICraftingCategoryExtension;
import mezz.jei.library.recipes.CraftingExtensionHelper;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;
import java.util.Set;

/**
 * Makes crafting-extension registration atomic across plugins.
 * <p>
 * {@code addRecipeExtension} checks a set and then adds to it. Two plugins registering the same
 * recipe class both pass the check and the second throws, which fails that plugin's whole
 * registration. The handler list and the identity cache are plain collections too, so a lookup
 * during registration walks them while another plugin appends. The first registration is kept.
 * <p>
 * Targets upstream 19.69.0.456.
 */
@Mixin(value = CraftingExtensionHelper.class, remap = false)
public abstract class CraftingExtensionHelperMixin {
	@Shadow
	private Set<Class<? extends CraftingRecipe>> handledClasses;

	@Unique
	private boolean jeiasync$inAdd;

	@Unique
	private boolean jeiasync$inGet;

	@Inject(method = "addRecipeExtension", at = @At("HEAD"), cancellable = true, remap = false)
	private <T extends CraftingRecipe> void jeiasync$lockAdd(Class<? extends T> recipeClass, ICraftingCategoryExtension<T> recipeExtension, CallbackInfo ci) {
		synchronized (this) {
			if (jeiasync$inAdd) {
				return;
			}
			MixinApplied.mark("collect.CraftingExtensionHelperMixin");
			if (recipeClass != null && this.handledClasses.contains(recipeClass)) {
				LogManager.getLogger().warn("JEI Async: kept the existing crafting extension for {}", recipeClass.getName());
				ci.cancel();
				return;
			}
			jeiasync$inAdd = true;
			try {
				this.addRecipeExtension(recipeClass, recipeExtension);
			} finally {
				jeiasync$inAdd = false;
			}
			ci.cancel();
		}
	}

	@Inject(method = "getOptionalRecipeExtension", at = @At("HEAD"), cancellable = true, remap = false)
	private <R extends CraftingRecipe> void jeiasync$lockGet(RecipeHolder<R> recipeHolder, CallbackInfoReturnable<Optional<ICraftingCategoryExtension<R>>> cir) {
		synchronized (this) {
			if (jeiasync$inGet) {
				return;
			}
			jeiasync$inGet = true;
			try {
				cir.setReturnValue(this.getOptionalRecipeExtension(recipeHolder));
			} finally {
				jeiasync$inGet = false;
			}
		}
	}

	@Shadow
	public abstract <R extends CraftingRecipe> Optional<ICraftingCategoryExtension<R>> getOptionalRecipeExtension(RecipeHolder<R> recipeHolder);

	@Shadow
	public abstract <T extends CraftingRecipe> void addRecipeExtension(Class<? extends T> recipeClass, ICraftingCategoryExtension<T> recipeExtension);
}
