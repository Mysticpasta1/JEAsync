package dev.jeasync.mixin.startup;

import dev.jeasync.JeiLoadProgress;
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
 */
@Mixin(value = GuiEventHandler.class, remap = false)
public abstract class GuiEventHandlerMixin {
	@Inject(method = "onGuiInit", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdInit(Screen screen, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "onGuiOpen", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdOpen(Screen screen, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "onClientTick", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdTick(CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "updateForScreenRender", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdUpdate(Screen screen, int mouseX, int mouseY, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "onDrawBackgroundPost", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdBackground(GuiGraphics guiGraphics, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "onDrawForeground", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdForeground(AbstractContainerScreen<?> screen, GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "onDrawForegroundAtIdentity", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdForegroundIdentity(GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "onDrawScreenPost", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdDraw(Screen screen, GuiGraphics guiGraphics, int mouseX, int mouseY, CallbackInfo ci) {
		if (!JeiLoadProgress.isGuiReady()) {
			ci.cancel();
		}
	}

	@Inject(method = "renderCompactPotionIndicators", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$holdPotions(AbstractContainerScreen<?> screen, CallbackInfoReturnable<Boolean> cir) {
		if (!JeiLoadProgress.isGuiReady()) {
			cir.setReturnValue(false);
		}
	}
}
