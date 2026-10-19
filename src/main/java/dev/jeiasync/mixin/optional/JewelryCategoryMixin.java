package dev.jeiasync.mixin.optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Gives {@link dev.jeiasync.mixin.optional.OptionalModMixinPlugin} a target so it can
 * retarget Tinkers Jewelry Ex onto the tconjei category class that replaced its parent.
 */
@Pseudo
@Mixin(targets = {
	"me.paypur.tconjei.jei.MaterialStatsCategory",
	"com.oooooohmy.tinkers_jewelry_ex.jei.JewelryStatsCategory"
}, remap = false)
public abstract class JewelryCategoryMixin {
}
