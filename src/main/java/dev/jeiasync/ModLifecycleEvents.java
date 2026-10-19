package dev.jeiasync;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lifecycle handlers, split by bus because the two buses are checked separately: a class
 * registered on the mod bus may only handle {@code IModBusEvent}, and one registered on the game
 * bus may only handle regular game events. Mixing them on a single instance is rejected at
 * construction time rather than silently ignored.
 */
final class ModLifecycleEvents {
	private static final Logger LOGGER = LogManager.getLogger();

	private ModLifecycleEvents() {
	}

	@EventBusSubscriber(modid = JeiAsyncMod.MOD_ID, bus = EventBusSubscriber.Bus.MOD)
	public static class ModBus {
		private ModBus() {
		}

		@SubscribeEvent
		public static void onCommonSetup(FMLCommonSetupEvent event) {
			// Nothing to set up: the mixins apply at class load and the worker pool creates
			// itself lazily on first use. The self-check just forces those targets to load.
			event.enqueueWork(MixinSelfCheck::run);
		}

		/** Logs the effective settings once the config file has been read. */
		@SubscribeEvent
		public static void onConfigLoading(ModConfigEvent.Loading event) {
			if (!event.getConfig().getModId().equals(JeiAsyncMod.MOD_ID)) {
				return;
			}
			AsyncPluginSupport.applyConfiguredThreads();
			LOGGER.info(
				"[async-diag] effective settings: threads={} recipeRowCapacity={}",
				AsyncConfig.getThreadCount(),
				AsyncConfig.getRecipeRowCapacity());
		}
	}

	@EventBusSubscriber(modid = JeiAsyncMod.MOD_ID, bus = EventBusSubscriber.Bus.GAME)
	public static class ForgeBus {
		private ForgeBus() {
		}

		@SubscribeEvent
		public static void onServerStopping(ServerStoppingEvent event) {
			AsyncPluginSupport.shutdown();
		}
	}
}