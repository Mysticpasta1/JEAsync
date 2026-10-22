package dev.jeiasync.mixin.collect;

import dev.jeiasync.AnvilEnchantCache;
import dev.jeiasync.AnvilResult;
import dev.jeiasync.MixinApplied;
import dev.jeiasync.PlainStacks;
import dev.jeiasync.mixin.nbt.ItemStackTagAccess;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.library.plugins.vanilla.anvil.AnvilRecipeMaker;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;

/**
 * Builds anvil book and repair outputs without the anvil menu, without a damage compound per
 * built stack, and without a fresh enchantment compound per item per level.
 * <p>
 * The category cache still covers enchantment checks that run before a book is accepted. The menu
 * is the call that handed every accepted pair to every anvil listener. While the scan runs, stack
 * constructors skip the throwaway {@code Damage:0} compound: the book outputs keep the
 * ingredient's own tag copy, and an undamaged plain tool repairs the same way with no compound.
 * <p>
 * A plain item's book output carries only the book's enchantment and a repair cost. The content
 * is decided by the book alone: the first item an empty tag builds that compound, and the other
 * items with an empty tag share it by reference. A stack with real data, a Tinkers tool or an
 * enchanted variant, keeps a full copy of its own tag, because that data is part of what the
 * anvil would have produced.
 * <p>
 * Targets upstream 15.74.0.227.
 */
@Mixin(value = AnvilRecipeMaker.class, remap = false)
public class AnvilRecipeMakerMixin {
	@Unique
	private static volatile boolean jeiasync$marked;

	/**
	 * Book stack identity to the {@code Enchantments} compound that a plain item's output of that
	 * book carries. Books are rebuilt per scan, so the map is thread-local and wiped when the
	 * scan's wrapper returns.
	 */
	@Unique
	private static final ThreadLocal<IdentityHashMap<ItemStack, CompoundTag>> jeiasync$bookOutputTags =
		ThreadLocal.withInitial(IdentityHashMap::new);

	/**
	 * @author Mysticpasta1
	 * @reason Each book level and each repair opened the anvil menu, so every listener handled that result. Build the output from the two stacks instead.
	 */
	@Overwrite
	private ItemStack getAnvilOutput(ItemStack leftStack, ItemStack rightStack) {
		jeiasync$mark();
		return AnvilResult.combine(leftStack, rightStack);
	}

	/**
	 * @author Mysticpasta1
	 * @reason The book path built one fresh compound per item per level. A result carrying only an empty ingredient's tag depends only on the book, so the first empty item builds it and the rest share it. Items carrying their own tag keep the full copy.
	 */
	@Overwrite
	private List<ItemStack> getEnchantedIngredients(ItemStack ingredient, List<ItemStack> enchantedBooks) {
		jeiasync$mark();
		List<ItemStack> outputs = new ArrayList<>(enchantedBooks.size());
		if (!PlainStacks.active()) {
			for (ItemStack enchantedBook : enchantedBooks) {
				ItemStack output = getAnvilOutput(ingredient, enchantedBook);
				if (!output.isEmpty()) {
					outputs.add(output);
				}
			}
			return outputs;
		}

		boolean emptyTags = jeiasync$hasEmptyTag(ingredient);
		IdentityHashMap<ItemStack, CompoundTag> bookOutputTags = jeiasync$bookOutputTags.get();
		for (ItemStack enchantedBook : enchantedBooks) {
			CompoundTag shared = emptyTags ? bookOutputTags.get(enchantedBook) : null;
			ItemStack output;
			if (shared != null) {
				output = new ItemStack(ingredient.getItem(), ingredient.getCount());
				((ItemStackTagAccess) (Object) output).jeiasync$setTag(shared);
			} else {
				output = getAnvilOutput(ingredient, enchantedBook);
				if (output.isEmpty()) {
					continue;
				}
				if (emptyTags) {
					CompoundTag tag = ((ItemStackTagAccess) (Object) output).jeiasync$getTag();
					if (tag != null && jeiasync$isPlainOverlay(tag) && !bookOutputTags.containsKey(enchantedBook)) {
						bookOutputTags.put(enchantedBook, tag);
					}
				}
			}
			outputs.add(output);
		}
		return outputs;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Registering recipes waited on anvil book levels, and each level rebuilt enchant categories for the same tool. Reuse them until this scan returns, and build the scan's stacks without the constructor damage compound.
	 */
	@Redirect(
		method = "getAnvilRecipes(Lmezz/jei/api/recipe/vanilla/IVanillaRecipeFactory;Lmezz/jei/api/runtime/IIngredientManager;Lnet/minecraft/world/inventory/AnvilMenu;)Ljava/util/List;",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/library/plugins/vanilla/anvil/AnvilRecipeMaker;getAnvilRecipes()Ljava/util/List;"
		)
	)
	private static List<IJeiAnvilRecipe> jeiasync$scanWithCategoryCache(AnvilRecipeMaker maker) {
		jeiasync$mark();
		AnvilEnchantCache.enter();
		PlainStacks.enter();
		try {
			return maker.getAnvilRecipes();
		} finally {
			AnvilEnchantCache.exit();
			PlainStacks.exit();
			jeiasync$bookOutputTags.get().clear();
			AnvilResult.clearScanCaches();
		}
	}

	/**
	 * A stack with no tag at all builds the same book result as another stack with no tag: the
	 * compound is the book's enchantment plus a repair cost.
	 */
	@Unique
	private static boolean jeiasync$hasEmptyTag(ItemStack stack) {
		CompoundTag tag = ((ItemStackTagAccess) (Object) stack).jeiasync$getTag();
		return tag == null || tag.isEmpty();
	}

	/**
	 * Whether the compound is only the enchantment list and a repair cost, the whole content a
	 * plain item's book result can have.
	 */
	@Unique
	private static boolean jeiasync$isPlainOverlay(CompoundTag tag) {
		int size = tag.size();
		if (size == 1) {
			return tag.contains("Enchantments");
		}
		return size == 2 && tag.contains("Enchantments") && tag.contains("RepairCost");
	}

	@Unique
	private static void jeiasync$mark() {
		if (jeiasync$marked) {
			return;
		}
		jeiasync$marked = true;
		MixinApplied.mark("collect.AnvilRecipeMakerMixin");
		org.apache.logging.log4j.LogManager.getLogger(AnvilRecipeMakerMixin.class).info(
			"JEI Async: anvil book and repair results are built without the anvil menu"
		);
		org.apache.logging.log4j.LogManager.getLogger(AnvilRecipeMakerMixin.class).info(
			"JEI Async: anvil scan stacks are built without the throwaway damage compound"
		);
		org.apache.logging.log4j.LogManager.getLogger(AnvilRecipeMakerMixin.class).info(
			"JEI Async: anvil book outputs for plain items share one compound per book"
		);
		org.apache.logging.log4j.LogManager.getLogger(AnvilRecipeMakerMixin.class).info(
			"JEI Async: anvil book outputs for tagged items share the tag content the book does not change"
		);
	}
}
