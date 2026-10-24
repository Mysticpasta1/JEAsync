package dev.jeasync.mixin.optional;

import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.world.item.DyeColor;

import dev.jeasync.AsyncConfig;
import dev.jeasync.MixinApplied;

/**
 * Stops Sophisticated Storage in Motion's recipe-viewer catalog from carrying a variant of every
 * storage item for every dye.
 * <p>
 * Both of its display-recipe makers build their variant lists the same way: the item's own creative
 * tab stacks, then one extra copy of the stack per {@code DyeColor} with that colour written onto
 * it, then one untinted sample. The catalog is built once and cached for the session, so a
 * pack with a few hundred moving-storage items ends up holding millions of them, and a dominator
 * tree of a modded client puts 155 MB and 14.6 million objects behind this one plugin.
 * <p>
 * Emptying the array the loop walks leaves the creative tab stacks and the untinted sample and drops
 * only the dyed copies. Those are the same recipe for every colour: JEI's subtype interpreter
 * already groups tinted storage into one ingredient, and what the moving-storage display shows for
 * the untinted item is unchanged.
 */
@Pseudo
@Mixin(targets = {
	"net.p3pp3rf1y.sophisticatedstorageinmotion.compat.recipeviewers.common.AssembleRecipesMaker",
	"net.p3pp3rf1y.sophisticatedstorageinmotion.compat.recipeviewers.common.MovingStorageTierUpgradeRecipesMaker"
}, remap = false)
public abstract class MovingStorageTintVariantsMixin {
	/**
	 * The dye list the loop walks. {@code remap = false} keeps this reference out of the refmap, so
	 * the Minecraft member is named as the running jar names it, the way the other mixins in this
	 * package do: {@code values()} of this enum is {@code m_150825_}.
	 */
	@Redirect(
		method = "addTintVariants",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/item/DyeColor;m_150825_()[Lnet/minecraft/world/item/DyeColor;"),
		remap = false,
		require = 0
	)
	private static DyeColor[] jeasync$withoutDyedVariants() {
		if (!AsyncConfig.capStorageTintVariants()) {
			return DyeColor.values();
		}
		if (!MixinApplied.isApplied("optional.MovingStorageTintVariantsMixin")) {
			MixinApplied.mark("optional.MovingStorageTintVariantsMixin");
			LogManager.getLogger().info(
				"jeasync: moving-storage displays list each storage item once instead of once per dye colour"
			);
		}
		return new DyeColor[0];
	}
}
