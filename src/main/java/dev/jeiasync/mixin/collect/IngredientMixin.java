package dev.jeiasync.mixin.collect;

import dev.jeiasync.AnvilEnchantCache;
import dev.jeiasync.MixinApplied;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Reuses one dissolved tag ingredient for the whole anvil or grindstone scan.
 * <p>
 * MCreator enchantments call {@code Ingredient.of(tag)} from {@code canApplyAtEnchantingTable}
 * for every item. Each call dissolves the tag into a fresh stack per match, and those stacks are
 * thrown away with the ingredient. The scan keeps the first ingredient and answers later tests
 * from the items in that one list.
 * <p>
 * Mysticpasta1 — Locating rebuilt sword, axe, pickaxe, and shovel tags once per enchantable item.
 */
@Mixin(Ingredient.class)
public abstract class IngredientMixin {
	@Unique
	private static volatile boolean jeiasync$marked;

	/**
	 * @author Mysticpasta1
	 * @reason The same tag is built again for every item in the grindstone scan. Return the ingredient this scan already dissolved.
	 */
	@Inject(
		method = "of(Lnet/minecraft/tags/TagKey;)Lnet/minecraft/world/item/crafting/Ingredient;",
		at = @At("HEAD"),
		cancellable = true
	)
	private static void jeiasync$reuseTag(TagKey<Item> tag, CallbackInfoReturnable<Ingredient> cir) {
		Ingredient cached = AnvilEnchantCache.tagIngredient(tag);
		if (cached != null) {
			cir.setReturnValue(cached);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason The first dissolve of a tag is the one later items should share. Record its items before the caller throws the ingredient away.
	 */
	@Inject(
		method = "of(Lnet/minecraft/tags/TagKey;)Lnet/minecraft/world/item/crafting/Ingredient;",
		at = @At("RETURN")
	)
	private static void jeiasync$keepTag(TagKey<Item> tag, CallbackInfoReturnable<Ingredient> cir) {
		if (!AnvilEnchantCache.active() || AnvilEnchantCache.tagIngredient(tag) != null) {
			return;
		}
		jeiasync$mark();
		AnvilEnchantCache.keepTagIngredient(tag, cir.getReturnValue());
	}

	/**
	 * @author Mysticpasta1
	 * @reason A rebuilt compound ingredient still walks every stack in the tag. The shared tag already knows its items.
	 */
	@Inject(method = "test(Lnet/minecraft/world/item/ItemStack;)Z", at = @At("HEAD"), cancellable = true)
	private void jeiasync$tagContains(ItemStack stack, CallbackInfoReturnable<Boolean> cir) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		Boolean hit = AnvilEnchantCache.tagContains((Ingredient) (Object) this, stack.getItem());
		if (hit != null) {
			cir.setReturnValue(hit);
		}
	}

	@Unique
	private static void jeiasync$mark() {
		if (jeiasync$marked) {
			return;
		}
		jeiasync$marked = true;
		MixinApplied.mark("collect.IngredientMixin");
		LogManager.getLogger(IngredientMixin.class).info(
			"JEI Async: anvil and grindstone scans reuse one dissolved tag ingredient per tag"
		);
	}
}
