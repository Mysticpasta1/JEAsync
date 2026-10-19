package dev.jeiasync.mixin.collect;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Optional;

/**
 * Makes {@link mezz.jei.forge.platform.ModHelper} safe for concurrent plugin registration.
 * <p>
 * Both of its caches are plain {@code HashMap}s populated through {@code computeIfAbsent}, and they
 * are read while building ingredient display names, which happens during registration.
 * {@code HashMap.computeIfAbsent} is not safe to call concurrently: the default implementation can
 * modify the map from inside its remapping function, so two threads racing on the same key can
 * corrupt the table or lose an entry.
 * <p>
 * Both fields are {@code private final}, so a mixin cannot swap them for a
 * {@code ConcurrentHashMap}; each compute is instead done under its own map's monitor. The icon
 * cache was added in 15.62 and needs the same treatment as the name cache.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.forge.platform.ModHelper.class, remap = false)
public abstract class ModHelperMixin {
	@Shadow
	private Map<String, String> cache;

	@Shadow
	private Map<String, Optional<byte[]>> modIconCache;

	@Shadow
	private String computeModNameForModId(String modId) {
		throw new AssertionError();
	}

	@Shadow
	private Optional<byte[]> computeModIconBytes(String modId) {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the name cache so workers do not race computeIfAbsent.
	 */
	@Overwrite
	public String getModNameForModId(String modId) {
		synchronized (cache) {
			return cache.computeIfAbsent(modId, this::computeModNameForModId);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the icon cache so workers do not race computeIfAbsent.
	 */
	@Overwrite
	public Optional<byte[]> getModIconBytes(String modId) {
		synchronized (modIconCache) {
			return modIconCache.computeIfAbsent(modId, this::computeModIconBytes);
		}
	}
}
