package dev.jeiasync;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mod entry point.
 * <p>
 * Most of this mod is mixins that patch upstream JEI; this class exists to give NeoForge something
 * to instantiate. Lifecycle handling lives in {@link ModLifecycleEvents}, because each bus rejects
 * handlers for the other bus at registration time.
 */
@Mod(JeiAsyncMod.MOD_ID)
public class JeiAsyncMod {
	public static final String MOD_ID = "jeiasync";

	private static final Logger LOGGER = LogManager.getLogger();

	/**
	 * The language loader injects the mod bus and the container. Config registration goes through
	 * the container.
	 */
	public JeiAsyncMod(IEventBus modBus, ModContainer container) {
		LOGGER.info("JEI Async loaded; targeting JEI {}", AsyncConfig.getTargetJeiVersion());
		// Resolve the game classloader on the mod-loading thread, before any worker is created
		// from a ForkJoin pool whose context loader cannot see mods.
		WorkerEnvironment.capture();

		// CLIENT, not COMMON. Everything these settings govern is local to this client's own
		// indexes and plugin dispatch, and none of it changes world state, so there is nothing for
		// a server to agree with a client about. A COMMON config would only add a network sync
		// whose disagreements have no correct resolution. See JeiAsyncConfig for the trade.
		container.registerConfig(ModConfig.Type.CLIENT, JeiAsyncConfig.SPEC);
	}
}