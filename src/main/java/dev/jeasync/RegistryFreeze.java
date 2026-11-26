package dev.jeasync;

import dev.jeasync.mixin.thread.MappedRegistryAccessor;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * Whether NeoForge has finished writing a registry.
 * <p>
 * Minecraft's {@code MappedRegistry} is not safe to read from another thread while a mod is still
 * registering into it: the id map and the name map are updated in sequence, and a lookup that
 * lands between the two writes can miss entries that were already stored. Lookups have to wait
 * until the registry is frozen. NeoForge freezes the item and block registries after mod loading;
 * the accessor mixin reads that flag without reflecting into the module.
 */
public final class RegistryFreeze {
	private RegistryFreeze() {
	}

	public static boolean itemsLocked() {
		return locked(BuiltInRegistries.ITEM);
	}

	public static boolean blocksLocked() {
		return locked(BuiltInRegistries.BLOCK);
	}

	private static boolean locked(Registry<?> registry) {
		if (registry instanceof MappedRegistryAccessor accessor) {
			return accessor.jeasync$isFrozen();
		}
		return true;
	}
}
