package dev.jeasync.mixin.startup;

import dev.jeasync.JeiLoadProgress;
import mezz.jei.forge.startup.EventRegistration;
import mezz.jei.forge.events.RuntimeEventSubscriptions;
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
 * {@code ForgeGuiPlugin.registerRuntime} runs on the startup worker. Registering onto
 * {@code MinecraftForge.EVENT_BUS} from that worker races a container screen that is already
 * rendering. The listeners are stored and applied on the client thread once the runtime exists.
 */
@Mixin(value = EventRegistration.class, remap = false)
public abstract class EventRegistrationMixin {
	@Inject(method = "registerEvents", at = @At("HEAD"), cancellable = true, remap = false)
	private static void jeasync$defer(RuntimeEventSubscriptions subscriptions, JeiEventHandlers handlers, CallbackInfo ci) {
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
