package dev.jeiasync.mixin.thread;

import dev.jeiasync.MixinApplied;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Optional;

/**
 * Resolves a block to the item currently in the registry.
 * <p>
 * {@code Block.asItem} remembers the first lookup. A worker that observes air writes air into that
 * field, and every later caller, including the startup retry, keeps drawing air. An overridden
 * registry entry has the same problem in the other direction: the block's delegate still returns
 * the old instance, and JEI then reports that instance as missing from the server.
 */
@Mixin(value = Block.class, remap = false)
public abstract class BlockAsItemMixin {
	@Shadow
	private Item item;

	@Inject(method = "asItem", at = @At("HEAD"), cancellable = true)
	private void jeiasync$resolveActiveItem(CallbackInfoReturnable<Item> cir) {
		MixinApplied.mark("thread.BlockAsItemMixin");
		Block self = (Block) (Object) this;
		Item seed = this.item;
		if (seed == null || seed == Items.AIR) {
			seed = Item.byBlock(self);
		}
		if (seed == null || seed == Items.AIR) {
			seed = itemWithSameId(self);
		}
		if (seed == null || seed == Items.AIR) {
			cir.setReturnValue(Items.AIR);
			return;
		}
		if (this.item == null || this.item == Items.AIR) {
			this.item = seed;
		}
		cir.setReturnValue(activeItem(seed));
	}

	private static Item itemWithSameId(Block block) {
		try {
			Optional<ResourceKey<Block>> key = BuiltInRegistries.BLOCK.getResourceKey(block);
			if (key.isEmpty()) {
				return Items.AIR;
			}
			Item item = BuiltInRegistries.ITEM.get(ResourceKey.create(Registries.ITEM, key.get().location()));
			return item == null ? Items.AIR : item;
		} catch (RuntimeException | LinkageError e) {
			return Items.AIR;
		}
	}

	private static Item activeItem(Item item) {
		try {
			Optional<ResourceKey<Item>> key = BuiltInRegistries.ITEM.getResourceKey(item);
			if (key.isEmpty()) {
				return item;
			}
			Item active = BuiltInRegistries.ITEM.get(key.get());
			if (active == null || active == Items.AIR) {
				return item;
			}
			return active;
		} catch (RuntimeException | LinkageError e) {
			return item;
		}
	}
}
