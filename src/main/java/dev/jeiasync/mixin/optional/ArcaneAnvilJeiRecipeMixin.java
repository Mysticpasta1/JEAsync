package dev.jeiasync.mixin.optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Gives {@link dev.jeiasync.mixin.optional.OptionalModMixinPlugin} a target so arcane anvil recipes
 * copy a stored spell tag instead of encoding it again.
 * <p>
 * Mysticpasta1 — imbue and affinity recipes encoded the same spell for every weapon and every spell.
 */
@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.jei.ArcaneAnvilJeiRecipe", remap = false)
public abstract class ArcaneAnvilJeiRecipeMixin {
}
