package dev.jeiasync.mixin.optional;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Just Enough Beacons asks Suffocation whether a block is a conduit frame during recipe registration,
 * when no level is loaded. Treat that as "not a frame" so the plugin finishes.
 */
@Pseudo
@Mixin(targets = "com.kozhi.suffocation.components.MultiBlock", remap = false)
public abstract class ConduitFrameMixin {
	@Inject(method = "isConduitFrame", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
	private void jeiasync$nullLevel(BlockState state, LevelReader level, BlockPos pos, BlockPos other, CallbackInfoReturnable<Boolean> cir) {
		if (level == null) {
			cir.setReturnValue(false);
		}
	}
}
