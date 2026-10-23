package dev.jeasync.mixin.thread;

import dev.jeasync.BlockItemMapLock;
import dev.jeasync.MixinApplied;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reads the block-to-item map under {@link BlockItemMapLock}.
 */
@Mixin(Item.class)
public abstract class ItemByBlockMixin {
	@Inject(method = "byBlock", at = @At("HEAD"), cancellable = true)
	private static void jeasync$lockedByBlock(Block block, CallbackInfoReturnable<Item> cir) {
		MixinApplied.mark("thread.ItemByBlockMixin");
		synchronized (BlockItemMapLock.LOCK) {
			cir.setReturnValue(Item.BY_BLOCK.getOrDefault(block, Items.AIR));
		}
	}
}
