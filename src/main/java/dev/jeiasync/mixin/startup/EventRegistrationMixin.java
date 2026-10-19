package dev.jeiasync.mixin.startup;

import dev.jeiasync.JeiLoadProgress;
import mezz.jei.neoforge.events.RuntimeEventSubscriptions;
import mezz.jei.neoforge.startup.EventRegistration;
import mezz.jei.gui.startup.JeiEventHandlers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves JEI's runtime screen listeners onto the client thread, after startup has finished.
 * <p>
 * {@code NeoForgeGuiPlugin.registerRuntime} runs on the startup worker. Registering onto the
 * game event bus from that worker races a container screen that is already rendering. The
 * listeners are stored and applied on the client thread once the runtime exists.
 */
@Mixin(value = EventRegistration.class, remap = false)
public abstract class EventRegistrationMixin {
	@Inject(method = "registerEvents", at = @At("HEAD"), cancellable = true, remap = false)
	private static void jeiasync$defer(RuntimeEventSubscriptions subscriptions, JeiEventHandlers handlers, CallbackInfo ci) {
		if (Minecraft.getInstance().isSameThread() && !JeiLoadProgress.isLoading()) {
			return;
		}
		ci.cancel();
		JeiLoadProgress.deferGuiRegistration(() -> {
			EventRegistration.registerEvents(subscriptions, handlers);
			// Init.Post already fired if the container opened while startup was running.
			Screen screen = Minecraft.getInstance().screen;
			if (screen != null) {
				handlers.guiEventHandler().onGuiInit(screen);
				handlers.clientInputHandler().onGuiChanged();
			}
		});
	}
}
