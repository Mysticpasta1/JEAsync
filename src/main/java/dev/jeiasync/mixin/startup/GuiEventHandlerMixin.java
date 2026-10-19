package dev.jeiasync.mixin.startup;

import dev.jeiasync.JeiLoadProgress;
import mezz.jei.gui.events.GuiEventHandler;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Keeps JEI's overlay off a container that opened before startup finished.
 * <p>
 * The listeners can be registered on the client thread a moment before the overlay has a screen.
 * Drawing or ticking in that window reads layout that {@code onGuiInit} has not filled in.
 * <p>
 * Targets upstream 19.69.0.456. Background, container foreground, non-container contents, and the
 * post-draw tooltips all go through {@code drawForScreenBackground} and {@code drawForScreenForeground}.
 */
@Mixin(value = GuiEventHandler.class, remap = false)
public abstract class GuiEventHandlerMixin {
	@Inject(method = "onGuiInit", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$holdInit(Screen screen, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "onGuiOpen", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$holdOpen(Screen screen, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "onClientTick", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$holdTick(CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "updateForScreenRender", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$holdUpdate(Screen screen, int mouseX, int mouseY, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "drawForScreenBackground", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$holdBackground(Screen screen, GuiGraphics guiGraphics, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "drawForScreenForeground", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$holdForeground(Screen screen, GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderCompactPotionIndicators", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$holdPotions(AbstractContainerScreen<?> screen, CallbackInfoReturnable<Boolean> cir) {
		if (!JeiLoadProgress.isGuiReady()) {
			cir.setReturnValue(false);
		}
	}
}
