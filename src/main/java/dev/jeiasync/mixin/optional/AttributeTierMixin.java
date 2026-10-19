package dev.jeiasync.mixin.optional;

import java.util.Map;
import java.util.function.Function;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Mekanism fills a plain {@code HashMap} of tier block types while several plugins run.
 * The only reader is {@code getPassthroughType}, so that lookup takes the map's lock.
 */
@Pseudo
@Mixin(targets = "mekanism.common.block.attribute.AttributeTier", remap = false)
public abstract class AttributeTierMixin {
	@SuppressWarnings({"rawtypes", "unchecked"})
	@Redirect(
		method = "getPassthroughType",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/Map;computeIfAbsent(Ljava/lang/Object;Ljava/util/function/Function;)Ljava/lang/Object;"
		),
		remap = false,
		require = 0
	)
	private static Object jeiasync$lockTierCache(Map cache, Object tier, Function lookup) {
		synchronized (cache) {
			return cache.computeIfAbsent(tier, lookup);
		}
	}
}
