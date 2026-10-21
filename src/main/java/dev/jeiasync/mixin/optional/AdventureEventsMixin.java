package dev.jeiasync.mixin.optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Gives {@link dev.jeiasync.mixin.optional.OptionalModMixinPlugin} a target so plain items skip
 * Apotheosis gem and affix work inside {@code enchLevels}.
 * <p>
 * Mysticpasta1 — each anvil result asked for enchantment levels, and the affix lookup ran on items
 * that have no affix data.
 */
@Pseudo
@Mixin(targets = "dev.shadowsoffire.apotheosis.adventure.AdventureEvents", remap = false)
public abstract class AdventureEventsMixin {
}
