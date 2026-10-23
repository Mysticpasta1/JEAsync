package dev.jeasync.mixin.optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Marks Mekanism's JEI recipe-type class so {@link OptionalModMixinPlugin} can lock registration.
 * {@code findType} drains a set into a map while other plugins add types. Both sides share the class lock.
 */
@Pseudo
@Mixin(targets = "mekanism.client.jei.MekanismJEIRecipeType", remap = false)
public abstract class MekanismJEIRecipeTypeMixin {
}
