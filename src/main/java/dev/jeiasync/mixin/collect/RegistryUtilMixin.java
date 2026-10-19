package dev.jeiasync.mixin.collect;

import dev.jeiasync.MixinApplied;
import mezz.jei.common.util.RegistryUtil;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceKey;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * Makes JEI's registry lookup safe for plugins that register at the same time.
 * <p>
 * {@code REGISTRY_CACHE} is a plain {@code HashMap} in a static field. Two plugins resolving a
 * registry on first use both see a miss and both write the map, which is the corruption
 * {@code HashMap} does not survive. The lock is the class, and {@link #getRegistryUncached} takes
 * it again through {@link #getRegistryAccess}, which is safe because {@code synchronized} reenters.
 * <p>
 * Targets upstream 19.69.0.456.
 */
@Mixin(value = RegistryUtil.class, remap = false)
public abstract class RegistryUtilMixin {
	@Shadow
	private static Map<ResourceKey<? extends Registry<?>>, Registry<?>> REGISTRY_CACHE;

	@Shadow
	@Nullable
	private static RegistryAccess REGISTRY_ACCESS;

	@Shadow
	private static Registry<?> getRegistryUncached(ResourceKey<? extends Registry<?>> key) {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the registry cache so a worker and the render thread share one lookup.
	 */
	@Overwrite
	public static <T> Registry<T> getRegistry(ResourceKey<? extends Registry<T>> key) {
		MixinApplied.mark("collect.RegistryUtilMixin");
		synchronized (RegistryUtil.class) {
			Registry<?> registry = REGISTRY_CACHE.get(key);
			if (registry == null) {
				registry = getRegistryUncached(key);
				REGISTRY_CACHE.put(key, registry);
			}
			@SuppressWarnings("unchecked")
			Registry<T> castRegistry = (Registry<T>) registry;
			return castRegistry;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock registry-access reads so a worker does not observe a half-published access.
	 */
	@Overwrite
	public static RegistryAccess getRegistryAccess() {
		synchronized (RegistryUtil.class) {
			if (REGISTRY_ACCESS == null) {
				Minecraft minecraft = Minecraft.getInstance();
				ClientLevel level = minecraft.level;
				if (level == null) {
					throw new IllegalStateException("Could not get registry, registry access is unavailable because the level is currently null");
				}
				REGISTRY_ACCESS = level.registryAccess();
			}
			return REGISTRY_ACCESS;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock registry-access replacement so a worker does not read the cache while it is being cleared.
	 */
	@Overwrite
	public static void setRegistryAccess(@Nullable RegistryAccess registryAccess) {
		synchronized (RegistryUtil.class) {
			REGISTRY_ACCESS = registryAccess;
			REGISTRY_CACHE.clear();
		}
	}
}
