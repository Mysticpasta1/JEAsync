package dev.jeiasync.mixin.load;

import java.util.List;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.library.load.registration.RecipeRegistration;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * An empty ingredient list used to abort the whole plugin. Skip that info entry and keep going.
 */
@Mixin(value = RecipeRegistration.class, remap = false)
public abstract class RecipeRegistrationMixin {
	@Inject(
		method = "addIngredientInfo(Ljava/util/List;Lmezz/jei/api/ingredients/IIngredientType;[Lnet/minecraft/network/chat/Component;)V",
		at = @At("HEAD"),
		cancellable = true,
		remap = false
	)
	private void jeiasync$skipEmptyInfo(List<?> ingredients, IIngredientType<?> ingredientType, Component[] descriptionComponents, CallbackInfo ci) {
		if (ingredients != null && ingredients.isEmpty()) {
			dev.jeiasync.MixinApplied.mark("load.RecipeRegistrationMixin");
			ci.cancel();
		}
	}
}
