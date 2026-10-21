package dev.jeiasync.mixin.optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Gives {@link OptionalModMixinPlugin} a target so anvil debug lines skip the tool name while
 * JEI registers recipes.
 * <p>
 * Mysticpasta1 — each rejected book enchant formatted the tool name during anvil recipes.
 */
@Pseudo
@Mixin(targets = "t.tinkerswithenchants.events.AnvilEnchantHandler", remap = false)
public abstract class AnvilEnchantHandlerMixin {
}
