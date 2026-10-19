package dev.jeiasync.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;

/**
 * Marks the ordinary JEI++ classes whose descriptors still name types JEI 15.71 removed.
 * {@link JeiPlusPlusMixinPlugin} rewrites those classes while they are mixed in.
 */
@Pseudo
@Mixin(
	targets = {
		"com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat",
		"com.lingmu0.JeiPlusPlusMod.client.CreativeTabGridCompat$CreativeTabInputHandler",
		"com.lingmu0.JeiPlusPlusMod.client.CreativeTabBar",
		"com.lingmu0.JeiPlusPlusMod.client.CreativeTabSelector",
		"com.lingmu0.JeiPlusPlusMod.client.IngredientListFeatures$GroupedIngredientElementHandler",
		"com.lingmu0.JeiPlusPlusMod.client.RecipeTreeFavorites$SyntheticElement",
		"com.lingmu0.JeiPlusPlusMod.client.RecipeTreeSidebarButton"
	},
	remap = false
)
public abstract class JeiPlusPlusClientClassMixin {
}
