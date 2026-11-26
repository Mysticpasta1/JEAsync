package dev.jeasync;

import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lifecycle handlers, split by bus because NeoForge's two buses are checked separately: a class
 * registered on the mod bus may only handle mod-loading events, and one registered on the game
 * bus may only handle regular game events. Mixing them on a single instance is rejected at
 * construction time rather than silently ignored.
 */
final class ModLifecycleEvents {
	private static final Logger LOGGER = LogManager.getLogger();

	private ModLifecycleEvents() {
	}

	@EventBusSubscriber(modid = JeAsyncMod.MOD_ID)
	public static class ModBus {
		private ModBus() {
		}

		@SubscribeEvent
		public static void onCommonSetup(FMLCommonSetupEvent event) {
			// Nothing to set up: the mixins apply at class load and the worker pool creates
			// itself lazily on first use. The self-check just forces those targets to load.
			event.enqueueWork(() -> {
				AsyncPluginSupport.finishModLoading();
				MixinSelfCheck.run();
			});
		}

		/** Logs the effective settings once NeoForge has read the config file. */
		@SubscribeEvent
		public static void onConfigLoading(ModConfigEvent.Loading event) {
			if (!event.getConfig().getModId().equals(JeAsyncMod.MOD_ID)) {
				return;
			}
			LOGGER.info(
				"[async-diag] effective settings: threads={} stallReportSeconds={} skippedRecipeSerializers={} dedupRecipeSerializers={} skippedCategories={} optimizeXycraft={}",
				AsyncConfig.getThreadCount(),
				AsyncConfig.stallReportSeconds(),
				AsyncConfig.skippedRecipeSerializers(),
				AsyncConfig.dedupRecipeSerializers(),
				AsyncConfig.skippedCategories(),
				AsyncConfig.optimizeXycraft());
		}
	}

	@EventBusSubscriber(modid = JeAsyncMod.MOD_ID)
	public static class GameBus {
		private GameBus() {
		}

		@SubscribeEvent
		public static void onServerStopping(ServerStoppingEvent event) {
			AsyncPluginSupport.shutdown();
		}
	}
}
