package dev.jeasync.mixin.load;

import dev.jeasync.MixinApplied;
import mezz.jei.common.platform.IPlatformRegistry;
import mezz.jei.library.plugins.vanilla.ingredients.ItemStackHelper;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
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
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = ItemStackHelper.class, remap = false)
public abstract class ItemStackHelperMixin {
	@Shadow
	private IPlatformRegistry<Item> itemRegistry;

	@Inject(method = "isIngredientOnServer", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$matchActiveItem(ItemStack ingredient, CallbackInfoReturnable<Boolean> cir) {
		MixinApplied.mark("load.ItemStackHelperMixin");
		Item item = ingredient.getItem();
		if (this.itemRegistry.contains(item)) {
			cir.setReturnValue(true);
			return;
		}
		try {
			Optional<ResourceKey<Item>> key = ForgeRegistries.ITEMS.getResourceKey(item);
			if (key.isEmpty()) {
				cir.setReturnValue(false);
				return;
			}
			Item active = ForgeRegistries.ITEMS.getValue(key.get().location());
			cir.setReturnValue(active != null && active != Items.AIR && this.itemRegistry.contains(active));
		} catch (RuntimeException | LinkageError e) {
			cir.setReturnValue(false);
		}
	}
}