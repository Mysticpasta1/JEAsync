package dev.jeasync.mixin.nbt;

import dev.jeasync.PlainStacks;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayDeque;

/**
 * Skips the damage tag a stack constructor writes, and serves the tag field of copies.
 * <p>
 * A damageable stack stores its damage in a compound. The constructor of the copy writes
 * {@code Damage:0} into a new compound, and {@code copy} then throws that compound away and
 * installs a copy of the source tag. Grindstone registration copies every enchanted input.
 * <p>
 * The same constructor write is skipped while {@link PlainStacks} is active, so scans that only
 * build display stacks do not pay for a compound per stack.
 * <p>
 * Mysticpasta1 — the plugin-loader snapshot spent those copies inside the thrown-away tag.
 */
@Mixin(ItemStack.class)
public abstract class ItemStackMixin {
	@Accessor("tag")
	public abstract CompoundTag jeiAsyncTag();

	@Shadow(remap = false)
	private void forgeInit() {
	}

	@Unique
	private static final ThreadLocal<ArrayDeque<ItemStack>> jeasync$copySources = ThreadLocal.withInitial(ArrayDeque::new);

	@Unique
	private static final ThreadLocal<ArrayDeque<int[]>> jeasync$copyDepths = ThreadLocal.withInitial(ArrayDeque::new);

	/**
	 * @author Mysticpasta1
	 * @reason The copy of a tagged damageable stack discards the compound the constructor just created. Build that copy without the discarded write.
	 */
	@Inject(method = "copy", at = @At("HEAD"), cancellable = true)
	private void jeasync$copyTagged(CallbackInfoReturnable<ItemStack> cir) {
		if (((ItemStack) (Object) this).isEmpty() || this.jeiAsyncTag() == null || !((ItemStack) (Object) this).getItem().isDamageable((ItemStack) (Object) this)) {
			return;
		}
		CompoundTag caps = ((CapabilityProviderAccess) (Object) this).jeasync$serializeCaps();
		jeasync$copySources.get().push((ItemStack) (Object) this);
		jeasync$copyDepths.get().push(new int[1]);
		try {
			ItemStack copy = new ItemStack(((ItemStack) (Object) this).getItem(), ((ItemStack) (Object) this).getCount(), caps);
			copy.setPopTime(((ItemStack) (Object) this).getPopTime());
			((ItemStackTagAccess) (Object) copy).jeasync$setTag(this.jeiAsyncTag().copy());
			cir.setReturnValue(copy);
		} finally {
			jeasync$copySources.get().pop();
			jeasync$copyDepths.get().pop();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Count constructors entered while a tagged copy is running, so only that copy skips its damage write. Mixin injects a constructor only at RETURN, and this call is the first point that can build a nested stack.
	 */
	@Redirect(
		method = "<init>(Lnet/minecraft/world/level/ItemLike;ILnet/minecraft/nbt/CompoundTag;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/ItemStack;forgeInit()V",
			remap = false
		)
	)
	private void jeasync$enterInit(ItemStack stack) {
		ArrayDeque<int[]> depths = jeasync$copyDepths.get();
		if (!depths.isEmpty()) {
			depths.peek()[0]++;
		}
		this.forgeInit();
	}

	/**
	 * @author Mysticpasta1
	 * @reason A nested stack constructed while the copy is running has left that constructor.
	 */
	@Inject(method = "<init>(Lnet/minecraft/world/level/ItemLike;ILnet/minecraft/nbt/CompoundTag;)V", at = @At("RETURN"))
	private void jeasync$leaveInit(ItemLike item, int count, CompoundTag caps, CallbackInfo ci) {
		ArrayDeque<int[]> depths = jeasync$copyDepths.get();
		if (!depths.isEmpty() && depths.peek()[0] > 0) {
			depths.peek()[0]--;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason The constructor writes Damage:0 and copy() replaces the whole tag. Skip that write for the stack this copy is building. isDamageable(ItemStack) is a Forge method and keeps that name, so the invoke is not remapped.
	 */
	@Redirect(
		method = "<init>(Lnet/minecraft/world/level/ItemLike;ILnet/minecraft/nbt/CompoundTag;)V",
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/item/Item;isDamageable(Lnet/minecraft/world/item/ItemStack;)Z",
			remap = false
		)
	)
	private boolean jeasync$skipThrowawayDamage(Item item, ItemStack stack) {
		if (PlainStacks.active()) {
			return false;
		}
		ArrayDeque<ItemStack> sources = jeasync$copySources.get();
		ArrayDeque<int[]> depths = jeasync$copyDepths.get();
		if (!sources.isEmpty()
			&& !depths.isEmpty()
			&& depths.peek()[0] == 1
			&& sources.peek().getItem() == stack.getItem()) {
			return false;
		}
		return item.isDamageable(stack);
	}
}
