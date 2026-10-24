package dev.jeasync;

import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.IForgeRegistryModifiable;

/**
 * Whether Forge has finished writing a registry.
 * <p>
 * {@code ForgeRegistry} keeps names and ids in {@code HashBiMap}s. A lookup from another thread
 * while a mod is still registering walks the map during a write, and the map drops entries that
 * were already stored. Vanilla ids such as {@code minecraft:glass} disappear along with mod ids.
 * Lookups have to wait until {@link IForgeRegistryModifiable#isLocked()}.
 */
public final class RegistryFreeze {
	private RegistryFreeze() {
	}

	public static boolean itemsLocked() {
		return locked(ForgeRegistries.ITEMS);
	}

	public static boolean blocksLocked() {
		return locked(ForgeRegistries.BLOCKS);
	}

	private static boolean locked(IForgeRegistry<?> registry) {
		return registry instanceof IForgeRegistryModifiable<?> modifiable && modifiable.isLocked();
	}
}
