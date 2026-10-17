package dev.jeiasync.mixin.collect;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * Makes {@link mezz.jei.forge.platform.ModHelper} safe for concurrent plugin registration.
 * <p>
 * Its display-name cache is a plain {@code HashMap} populated through
 * {@code computeIfAbsent}, and it is read while building ingredient display names, which happens
 * during registration. {@code HashMap.computeIfAbsent} is not safe to call concurrently: the
 * default implementation can modify the map from inside its remapping function, so two threads
 * racing on the same key can corrupt the table or lose an entry.
 * <p>
 * The field is {@code private final}, so a mixin cannot swap it for a {@code ConcurrentHashMap};
 * the whole compute is instead done under the map's monitor.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.forge.platform.ModHelper.class, remap = false)
public abstract class ModHelperMixin {
	@Shadow
	private Map<String, String> cache;

	@Shadow
	private String computeModNameForModId(String modId) {
		throw new AssertionError();
	}

	@Overwrite
	public String getModNameForModId(String modId) {
		synchronized (cache) {
			return cache.computeIfAbsent(modId, this::computeModNameForModId);
		}
	}
}