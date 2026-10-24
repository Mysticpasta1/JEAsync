package dev.jeasync.mixin.optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Gives {@link dev.jeasync.mixin.optional.OptionalModMixinPlugin} a target so the arcane anvil
 * recipe list is replaced with one recipe of each type before the cross product is built.
 * <p>
 * Mysticpasta1 — the arcane anvil wrote the same cross products JEI's own anvil scan does.
 */
@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.jei.ArcaneAnvilRecipeMaker", remap = false)
public abstract class ArcaneAnvilRecipeMakerMixin {
}
