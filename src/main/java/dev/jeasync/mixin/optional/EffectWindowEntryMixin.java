package dev.jeasync.mixin.optional;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.world.food.FoodProperties;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * JEED reads every food's effects. A KubeJS food whose effect id is not registered used to abort
 * the whole plugin. That food contributes no effects.
 */
@Pseudo
@Mixin(targets = "net.mehvahdjukaar.jeed.common.EffectWindowEntry", remap = false)
public abstract class EffectWindowEntryMixin {
	private static final AtomicBoolean LOGGED = new AtomicBoolean();

	@Redirect(
		method = "buildStaticCache",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/food/FoodProperties;m_38749_()Ljava/util/List;"),
		remap = false,
		require = 0
	)
	private static List<?> jeasync$foodEffects(FoodProperties food) {
		try {
			return food.getEffects();
		} catch (RuntimeException failure) {
			if (LOGGED.compareAndSet(false, true)) {
				LogManager.getLogger().warn("jeasync: a food effect id is not registered, so that food is left out of JEED");
			}
			return List.of();
		}
	}
}
