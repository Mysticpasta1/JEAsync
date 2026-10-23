package dev.jeasync;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Mod entry point.
 * <p>
 * Most of this mod is mixins that patch upstream JEI; this class exists to give Forge something to
 * instantiate. Lifecycle handling lives in {@link ModLifecycleEvents}, because Forge validates
 * every {@code @SubscribeEvent} method when the instance is registered and rejects handlers for
 * the wrong bus outright.
 */
@Mod(JeAsyncMod.MOD_ID)
public class JeAsyncMod {
	public static final String MOD_ID = "jeasync";

	private static final Logger LOGGER = LogManager.getLogger();

	/**
	 * Takes the loading context rather than calling {@code ModLoadingContext.get()}, which Forge has
	 * deprecated for removal in favour of the context the language loader passes in.
	 * {@code FMLModContainer.constructMod} looks for a constructor of this shape before falling back
	 * to a no-arg one, so this is the supported route and not an incidental overload.
	 */
	public JeAsyncMod(FMLJavaModLoadingContext context) {
		LOGGER.info("jeasync loaded; targeting JEI {}", AsyncConfig.getTargetJeiVersion());
		// Resolve the game classloader on the mod-loading thread, before any worker is created
		// from a ForkJoin pool whose context loader cannot see mods.
		WorkerEnvironment.capture();

		// CLIENT, not COMMON. Everything these settings govern is local to this client's own
		// indexes and plugin dispatch, and none of it changes world state, so there is nothing for
		// a server to agree with a client about. A COMMON config would only add a network sync
		// whose disagreements have no correct resolution. See JeAsyncConfig for the trade.
		context.registerConfig(ModConfig.Type.CLIENT, JeAsyncConfig.SPEC);
	}
}