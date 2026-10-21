package dev.jeiasync.mixin.collect;

import dev.jeiasync.JeiLoadProgress;
import dev.jeiasync.MixinApplied;
import dev.jeiasync.TypedIngredientIntern;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.common.ingredients.itemStacks.TypedItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Stops recipe registration from pinning every item stack it touches.
 * <p>
 * {@link TypedItemStack#getIngredient()} stores the stack in a cache that keeps it for a second
 * after the last read. The cache key is the wrapper itself, so two equal items never share an
 * entry. Registration creates a new wrapper per ingredient and reads it once, which means the
 * cache never hits and still holds about a second of every expanded ingredient. Parallel plugins
 * make that window large, and the heap grows to fit it.
 * <p>
 * While the heavy search indexes are still deferred, return a stack and let it die with the
 * recipe that asked for it. Search runs later and uses the cache, because those wrappers stay
 * alive and the hit saves a stack.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(value = TypedItemStack.class, remap = false)
public abstract class TypedItemStackMixin {
	@Shadow
	protected abstract ItemStack createItemStackUncached();

	@Unique
	private static volatile boolean jeiasync$marked;

	@Inject(method = "getIngredient", at = @At("HEAD"), cancellable = true)
	private void jeiasync$skipStartupCache(CallbackInfoReturnable<ItemStack> cir) {
		if (!JeiLoadProgress.isDeferringHeavySearch()) {
			return;
		}
		if (!jeiasync$marked) {
			jeiasync$marked = true;
			MixinApplied.mark("collect.TypedItemStackMixin");
		}
		cir.setReturnValue(this.createItemStackUncached());
	}

	/**
	 * @author Mysticpasta1
	 * @reason JEI copies the tag here and the typed-stack constructor copies it again. Keep the caller's tag. An empty tag is the same as no tag, so the recipe item does not keep an empty compound.
	 */
	@Redirect(
		method = "create(Lnet/minecraft/world/item/ItemStack;)Lmezz/jei/api/ingredients/ITypedIngredient;",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/common/ingredients/itemStacks/TypedItemStack;copyTag(Lnet/minecraft/nbt/CompoundTag;)Lnet/minecraft/nbt/CompoundTag;"
		),
		remap = false
	)
	private static CompoundTag jeiasync$useCallerTag(CompoundTag tag) {
		if (tag == null || tag.isEmpty()) {
			return null;
		}
		return tag;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Share plain items. Tagged stacks in this pack are unique, and interning them pinned a second nbt copy.
	 */
	@Inject(
		method = "create(Lnet/minecraft/world/item/ItemStack;)Lmezz/jei/api/ingredients/ITypedIngredient;",
		at = @At("HEAD"),
		cancellable = true,
		remap = false
	)
	private static void jeiasync$internStack(ItemStack ingredient, CallbackInfoReturnable<ITypedIngredient<ItemStack>> cir) {
		ITypedIngredient<ItemStack> shared = TypedIngredientIntern.internStack(ingredient);
		if (shared != null) {
			cir.setReturnValue(shared);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Plain items are the common recipe slot. Share the wrapper the item factory returns.
	 */
	@Inject(
		method = "create(Lnet/minecraft/world/level/ItemLike;)Lmezz/jei/api/ingredients/ITypedIngredient;",
		at = @At("HEAD"),
		cancellable = true,
		remap = false
	)
	private static void jeiasync$internItem(ItemLike itemLike, CallbackInfoReturnable<ITypedIngredient<ItemStack>> cir) {
		ITypedIngredient<ItemStack> shared = TypedIngredientIntern.internItem(itemLike);
		if (shared != null) {
			cir.setReturnValue(shared);
		}
	}
}
