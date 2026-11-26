package dev.jeasync.mixin.thread;

import net.minecraft.core.MappedRegistry;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the frozen flag NeoForge sets after mod registration finished. The flag has no public
 * getter on this version, and reading it is how the add-on decides whether a registry lookup is
 * safe from a worker thread.
 */
@Mixin(MappedRegistry.class)
public interface MappedRegistryAccessor {
	@Accessor("frozen")
	boolean jeasync$isFrozen();
}
