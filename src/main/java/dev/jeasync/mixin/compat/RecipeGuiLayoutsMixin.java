package dev.jeasync.mixin.compat;

import java.util.Optional;

import dev.jeasync.compat.JeiPlusPlusHooks;
import mezz.jei.common.input.IInternalKeyMappings;
import mezz.jei.common.input.UserInput;
import mezz.jei.common.input.interaction.IInputInteraction;
import mezz.jei.gui.recipes.RecipeGuiLayouts;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Recipe-tree clicks used to arrive through {@code RecipesGui.mouseClicked}, which JEI 15.71 removed.
 * The layout input target is what the screen calls now, including while Forge builds the GUI off the render thread.
 */
@Mixin(value = RecipeGuiLayouts.class, remap = false)
public abstract class RecipeGuiLayoutsMixin {
	@Inject(method = "beginInput", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$recipeTreePress(
		Screen screen,
		UserInput input,
		IInternalKeyMappings keys,
		CallbackInfoReturnable<Optional<IInputInteraction>> cir
	) {
		if (JeiPlusPlusHooks.recipeTreePress(this, input) instanceof Optional<?> handled) {
			cir.setReturnValue(cast(handled));
		}
	}

	@SuppressWarnings("unchecked")
	private static Optional<IInputInteraction> cast(Optional<?> handled) {
		return (Optional<IInputInteraction>) handled;
	}
}
