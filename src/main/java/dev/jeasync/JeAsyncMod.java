package dev.jeasync;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mod entry point.
 * <p>
 * Most of this mod is mixins that patch upstream JEI; this class exists to give NeoForge
 * something to instantiate. Lifecycle handling lives in {@link ModLifecycleEvents}, because
 * NeoForge validates every {@code @SubscribeEvent} method when the instance is registered and
 * rejects handlers for the wrong bus outright.
 */
@Mod(JeAsyncMod.MOD_ID)
public class JeAsyncMod {
	public static final String MOD_ID = "jeasync";

	private static final Logger LOGGER = LogManager.getLogger();

	public JeAsyncMod(IEventBus modEventBus, ModContainer modContainer) {
		LOGGER.info("jeasync loaded; targeting JEI {}", AsyncConfig.getTargetJeiVersion());
		// Resolve the game classloader on the mod-loading thread, before any worker is created
		// from a ForkJoin pool whose context loader cannot see mods.
		WorkerEnvironment.capture();

		// CLIENT, not COMMON. Everything these settings govern is local to this client's own
		// indexes and plugin dispatch, and none of it changes world state, so there is nothing for
		// a server to agree with a client about. A COMMON config would only add a network sync
		// whose disagreements have no correct resolution. See JeAsyncConfig for the trade.
		modContainer.registerConfig(ModConfig.Type.CLIENT, JeAsyncConfig.SPEC);
	}
}
