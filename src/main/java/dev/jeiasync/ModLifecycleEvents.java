package dev.jeiasync;

import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.config.ModConfigEvent;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Lifecycle handlers, split by bus because Forge's two buses are checked separately: a class
 * registered on the mod bus may only handle {@code IModBusEvent}, and one registered on the Forge
 * bus may only handle regular game events. Mixing them on a single instance is rejected at
 * construction time rather than silently ignored.
 */
final class ModLifecycleEvents {
	private static final Logger LOGGER = LogManager.getLogger();

	private ModLifecycleEvents() {
	}

	@Mod.EventBusSubscriber(modid = JeiAsyncMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD)
	public static class ModBus {
		private ModBus() {
		}

		@SubscribeEvent
		public static void onCommonSetup(FMLCommonSetupEvent event) {
			// Nothing to set up: the mixins apply at class load and the worker pool creates
			// itself lazily on first use. The self-check just forces those targets to load.
			event.enqueueWork(MixinSelfCheck::run);
		}

		/** Logs the effective settings once Forge has read the config file. */
		@SubscribeEvent
		public static void onConfigLoading(ModConfigEvent.Loading event) {
			if (!event.getConfig().getModId().equals(JeiAsyncMod.MOD_ID)) {
				return;
			}
			LOGGER.info(
				"[async-diag] effective settings: threads={} recipeRowCapacity={}",
				AsyncConfig.getThreadCount(),
				AsyncConfig.getRecipeRowCapacity());
		}
	}

	@Mod.EventBusSubscriber(modid = JeiAsyncMod.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
	public static class ForgeBus {
		private ForgeBus() {
		}

		@SubscribeEvent
		public static void onServerStopping(ServerStoppingEvent event) {
			AsyncPluginSupport.shutdown();
		}
	}
}