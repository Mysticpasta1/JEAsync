package dev.jeasync.mixin.compat;

import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Keeps Mekanism's incomplete-recipe scanner from calling {@code Ingredient.getValues()} on a
 * NeoForge custom ingredient.
 * <p>
 * Mekanism uses {@code isSimple()} as a stand-in for "has vanilla values". That is not the same
 * thing in 1.21: simple custom ingredients such as {@code CompoundIngredient} report
 * {@code isSimple() == true} while {@code getValues()} throws by contract. When a recipe is
 * incomplete because a custom ingredient resolved to no items, the scanner crashes the server on
 * startup instead of logging the missing tag.
 * <p>
 * Returning an empty value array for a custom ingredient lets Mekanism's existing loop do nothing
 * for that ingredient; the other branches of the log method are unaffected.
 */
@Mixin(targets = "mekanism.api.recipes.ingredients.ItemStackIngredient", remap = false)
public abstract class MekanismItemStackIngredientMixin {
	@Redirect(
		method = "logMissingTags",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/crafting/Ingredient;getValues()[Lnet/minecraft/world/item/crafting/Ingredient$Value;"
		),
		remap = false
	)
	private Ingredient.Value[] jeasync$safeValues(Ingredient ingredient) {
		if (ingredient.isCustom()) {
			return new Ingredient.Value[0];
		}
		return ingredient.getValues();
	}
}
