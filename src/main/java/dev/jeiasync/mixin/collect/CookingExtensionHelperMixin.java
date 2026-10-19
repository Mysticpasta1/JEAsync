package dev.jeiasync.mixin.collect;

import dev.jeiasync.MixinApplied;
import mezz.jei.api.recipe.category.extensions.vanilla.cooking.ICookingCategoryExtension;
import mezz.jei.library.recipes.CookingExtensionHelper;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Map;

/**
 * Makes cooking-extension registration atomic across plugins.
 * <p>
 * {@code addExtension} checks the map and then writes it, and {@code getRecipeExtension} resolves
 * into a second map. Both are plain {@code HashMap}s. A lookup that walks the key set while
 * another plugin inserts can miss the new extension or throw. The first registration is kept, and
 * the resolve cache is only cleared by the original add, which still runs under the same lock.
 * <p>
 * Targets upstream 19.69.0.456.
 */
@Mixin(value = CookingExtensionHelper.class, remap = false)
public abstract class CookingExtensionHelperMixin {
	@Shadow
	private Map<Class<?>, ICookingCategoryExtension<?>> extensions;

	@Unique
	private boolean jeiasync$inAdd;

	@Unique
	private boolean jeiasync$inGet;

	@Inject(method = "addExtension", at = @At("HEAD"), cancellable = true, remap = false)
	private <R> void jeiasync$lockAdd(Class<? extends R> recipeClass, ICookingCategoryExtension<R> extension, CallbackInfo ci) {
		synchronized (this) {
			if (jeiasync$inAdd) {
				return;
			}
			MixinApplied.mark("collect.CookingExtensionHelperMixin");
			if (recipeClass != null && this.extensions.containsKey(recipeClass)) {
				LogManager.getLogger().warn("JEI Async: kept the existing cooking extension for {}", recipeClass.getName());
				ci.cancel();
				return;
			}
			jeiasync$inAdd = true;
			try {
				this.addExtension(recipeClass, extension);
			} finally {
				jeiasync$inAdd = false;
			}
			ci.cancel();
		}
	}

	@Inject(method = "getRecipeExtension", at = @At("HEAD"), cancellable = true, remap = false)
	private <R extends AbstractCookingRecipe> void jeiasync$lockGet(R recipe, CallbackInfoReturnable<ICookingCategoryExtension<? super R>> cir) {
		synchronized (this) {
			if (jeiasync$inGet) {
				return;
			}
			jeiasync$inGet = true;
			try {
				cir.setReturnValue(this.getRecipeExtension(recipe));
			} finally {
				jeiasync$inGet = false;
			}
		}
	}

	@Shadow
	public abstract <R> void addExtension(Class<? extends R> recipeClass, ICookingCategoryExtension<R> extension);

	@Shadow
	@Nullable
	public abstract <R extends AbstractCookingRecipe> ICookingCategoryExtension<? super R> getRecipeExtension(R recipe);
}
