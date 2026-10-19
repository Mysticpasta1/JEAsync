package dev.jeiasync.mixin.optional;

import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.ItemStack;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Some items pass the food filter and still have no food properties. That null used to abort
 * every Industrial Foregoing generator recipe.
 */
@Pseudo
@Mixin(targets = "com.buuz135.industrial.block.generator.mycelial.CulinaryGeneratorType", remap = false)
public abstract class CulinaryGeneratorTypeMixin {
	@Inject(method = "calculate", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void jeiasync$emptyFood(ItemStack stack, CallbackInfoReturnable<Pair<Integer, Integer>> cir) {
		FoodProperties food = stack.getItem().getFoodProperties(stack, null);
		if (food == null) {
			cir.setReturnValue(Pair.of(0, 0));
		}
	}
}
