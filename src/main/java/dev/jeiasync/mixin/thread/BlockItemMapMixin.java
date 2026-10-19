package dev.jeiasync.mixin.thread;

import dev.jeiasync.BlockItemMapLock;
import dev.jeiasync.MixinApplied;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Map;

/**
 * Writes the block-to-item map under {@link BlockItemMapLock}.
 */
@Mixin(BlockItem.class)
public abstract class BlockItemMapMixin {
	@Inject(method = "registerBlocks", at = @At("HEAD"), cancellable = true)
	private void jeiasync$lockedRegister(Map<Block, Item> map, Item item, CallbackInfo ci) {
		MixinApplied.mark("thread.BlockItemMapMixin");
		ci.cancel();
		synchronized (BlockItemMapLock.LOCK) {
			map.put(((BlockItem) (Object) this).getBlock(), item);
		}
	}

	@Inject(method = "removeFromBlockToItemMap", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$lockedRemove(Map<Block, Item> map, Item item, CallbackInfo ci) {
		MixinApplied.mark("thread.BlockItemMapMixin");
		ci.cancel();
		synchronized (BlockItemMapLock.LOCK) {
			map.remove(((BlockItem) (Object) this).getBlock());
		}
	}
}
