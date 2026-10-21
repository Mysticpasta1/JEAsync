package dev.jeiasync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

/**
 * Plain items have nothing for Apotheosis to add to an enchantment level.
 * <p>
 * Gems and affixes are stored under {@code affix_data}. Looking them up still posts a socket
 * event and builds an empty affix map, and JEI's anvil scan does that for every book level.
 * Mysticpasta1 — the plugin thread was in {@code AdventureEvents.enchLevels} for that reason.
 */
public final class AffixLevelSkip {
	private AffixLevelSkip() {
	}

	/** True when the enchantment map cannot change, so the affix listener can return. */
	public static boolean plain(ItemStack stack) {
		if (stack == null) {
			return true;
		}
		CompoundTag tag = stack.getTag();
		return tag == null || !tag.contains("affix_data");
	}
}
