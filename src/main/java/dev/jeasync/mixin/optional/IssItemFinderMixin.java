package dev.jeasync.mixin.optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Gives {@link dev.jeasync.mixin.optional.OptionalModMixinPlugin} a target so Iron's Spells
 * classifies one stack per item while it registers recipes.
 * <p>
 * Mysticpasta1 — the finder walked every JEI stack to decide which items the arcane anvil can use.
 */
@Pseudo
@Mixin(targets = "io.redspace.ironsspellbooks.jei.JeiPlugin$ItemFinder", remap = false)
public abstract class IssItemFinderMixin {
}
