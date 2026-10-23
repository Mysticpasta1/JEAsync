package dev.jeasync.mixin.optional;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.Ingredient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Spawn registers the blue-footed boots material with a null repair tag.
 * {@code Ingredient.of(null)} then throws while JEI builds anvil recipes.
 */
@Pseudo
@Mixin(targets = "com.ninni.spawn.server.item.SpawnArmorMaterial", remap = false)
public abstract class SpawnArmorMaterialMixin {
	@Shadow(remap = false)
	private TagKey<Item> repairedBy;

	@Inject(method = {"m_6230_", "getRepairIngredient"}, at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void jeasync$emptyRepairTag(CallbackInfoReturnable<Ingredient> cir) {
		if (this.repairedBy == null) {
			cir.setReturnValue(Ingredient.EMPTY);
		}
	}
}
