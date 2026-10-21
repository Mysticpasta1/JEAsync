package dev.jeiasync;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Supplies the tool name for Tinkers with Enchants anvil debug lines.
 * <p>
 * That listener passes the name into {@code Logger.debug} on every book enchant the tool cannot
 * take. Log4j builds the argument before it checks the level, and the name walks hover text
 * through a message format. JEI's anvil scan hits that path once per rejected enchant.
 * <p>
 * Mysticpasta1 — the plugin thread was in that format at anvil recipe registration.
 */
public final class AnvilEnchantNames {
	private static final Logger TWE = LogManager.getLogger("tinkerswithenchants");

	private AnvilEnchantNames() {
	}

	/**
	 * The string the debug line would have printed.
	 * Recipe registration leaves it blank. A normal anvil use still returns the tool name when
	 * the tinkerswithenchants logger is at debug.
	 */
	public static String display(ItemStack stack) {
		if (stack == null || AnvilEnchantCache.active() || !TWE.isDebugEnabled()) {
			return "";
		}
		Component name = stack.getDisplayName();
		return name == null ? "" : name.getString();
	}
}
