package dev.jeiasync;

import net.minecraftforge.fml.common.Mod;
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
@Mod(JeiAsyncMod.MOD_ID)
public class JeiAsyncMod {
	public static final String MOD_ID = "jeiasync";

	private static final Logger LOGGER = LogManager.getLogger();

	public JeiAsyncMod() {
		LOGGER.info("JEI Async loaded; targeting JEI {}", AsyncConfig.getTargetJeiVersion());
	}
}