package dev.jeasync.mixin.thread;

import dev.jeasync.MixinApplied;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
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
@Mixin(Block.class)
public abstract class BlockAsItemMixin {
	@Accessor("item")
	public abstract Item jeiAsyncItem();

	@Accessor("item")
	public abstract void jeiAsyncSetItem(Item item);

	@Inject(method = "asItem", at = @At("HEAD"), cancellable = true)
	private void jeasync$resolveActiveItem(CallbackInfoReturnable<Item> cir) {
		MixinApplied.mark("thread.BlockAsItemMixin");
		Block self = (Block) (Object) this;
		Item seed = this.jeiAsyncItem();
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
		if (this.jeiAsyncItem() == null || this.jeiAsyncItem() == Items.AIR) {
			this.jeiAsyncSetItem(seed);
		}
		cir.setReturnValue(activeItem(seed));
	}

	private static Item itemWithSameId(Block block) {
		try {
			Optional<ResourceKey<Block>> key = ForgeRegistries.BLOCKS.getResourceKey(block);
			if (key.isEmpty()) {
				return Items.AIR;
			}
			Item item = ForgeRegistries.ITEMS.getValue(key.get().location());
			return item == null ? Items.AIR : item;
		} catch (RuntimeException | LinkageError e) {
			return Items.AIR;
		}
	}

	private static Item activeItem(Item item) {
		try {
			Optional<ResourceKey<Item>> key = ForgeRegistries.ITEMS.getResourceKey(item);
			if (key.isEmpty()) {
				return item;
			}
			Item active = ForgeRegistries.ITEMS.getValue(key.get().location());
			if (active == null || active == Items.AIR) {
				return item;
			}
			return active;
		} catch (RuntimeException | LinkageError e) {
			return item;
		}
	}
}
