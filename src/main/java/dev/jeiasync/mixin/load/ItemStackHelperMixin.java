package dev.jeiasync.mixin.load;

import dev.jeiasync.MixinApplied;
import mezz.jei.common.util.RegistryUtil;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Accepts a stack whose instance was replaced in the item registry.
 * <p>
 * JEI asks the registry whether it contains that exact object. After a registry override the
 * creative tab and the category icon still hold the old object, so the check fails and the icon
 * path throws. The id is still registered. The object currently stored under that id is the one
 * the server has.
 * <p>
 * Targets upstream 19.69.0.456.
 */
@Mixin(value = ItemStackHelper.class, remap = false)
public abstract class ItemStackHelperMixin {
	@Inject(method = "isIngredientOnServer", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$matchActiveItem(ItemStack ingredient, CallbackInfoReturnable<Boolean> cir) {
		MixinApplied.mark("load.ItemStackHelperMixin");
		Item item = ingredient.getItem();
		try {
			if (RegistryUtil.getRegistry(Registries.ITEM).getKey(item) != null) {
				cir.setReturnValue(true);
				return;
			}
			Optional<ResourceKey<Item>> key = BuiltInRegistries.ITEM.getResourceKey(item);
			if (key.isEmpty()) {
				cir.setReturnValue(false);
				return;
			}
			Item active = BuiltInRegistries.ITEM.get(key.get());
			cir.setReturnValue(active != null && active != Items.AIR && RegistryUtil.getRegistry(Registries.ITEM).getKey(active) != null);
		} catch (RuntimeException | LinkageError e) {
			cir.setReturnValue(false);
		}
	}
}
