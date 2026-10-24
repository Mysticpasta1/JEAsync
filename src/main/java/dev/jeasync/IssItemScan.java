package dev.jeasync;

import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * One representative stack per item, for Iron's Spells' arcane anvil item scan.
 * <p>
 * The finder walks every registered {@link ItemStack} to decide which items the arcane anvil can
 * imbue, upgrade, or attune, and it only ever reads {@link ItemStack#getItem()}. JEI's own ingredient
 * list can carry many thousands of stacks that share one item, so the scan does the same classification
 * over and over. {@link dev.jeasync.compat.ArcaneRegistration} rewrites the finder's
 * {@code getAllItemStacks} call to {@link #representative} so each item is classified once.
 * <p>
 * Mysticpasta1 — the finder walked every JEI stack to decide which items the arcane anvil can use.
 */
public final class IssItemScan {
	private static final Logger LOGGER = LogManager.getLogger(IssItemScan.class);
	private static volatile boolean marked;

	private IssItemScan() {
	}

	/**
	 * A collection holding one stack for each distinct item the manager knows about. The argument is
	 * the {@link IIngredientManager} the finder was built with; the rewritten call site passes it as
	 * the receiver of the {@code getAllItemStacks} it replaces.
	 */
	public static Collection<ItemStack> representative(Object manager) {
		if (!(manager instanceof IIngredientManager ingredientManager)) {
			return manager instanceof Collection<?> collection ? asStacks(collection) : new ArrayList<>();
		}
		Collection<ItemStack> all;
		try {
			all = ingredientManager.getAllItemStacks();
		} catch (RuntimeException failure) {
			LOGGER.warn("jeasync: arcane anvil fell back to the full item list", failure);
			return new ArrayList<>();
		}
		Map<Item, ItemStack> byItem = new IdentityHashMap<>();
		for (ItemStack stack : all) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			Item item = stack.getItem();
			byItem.putIfAbsent(item, stack);
		}
		if (!marked) {
			marked = true;
			MixinApplied.mark("compat.IssItemScan");
			LOGGER.info(
				"jeasync: arcane anvil classifies {} item(s) instead of {} stack(s)",
				byItem.size(),
				all.size()
			);
		}
		return new ArrayList<>(byItem.values());
	}

	private static Collection<ItemStack> asStacks(Collection<?> collection) {
		ArrayList<ItemStack> stacks = new ArrayList<>(collection.size());
		for (Object element : collection) {
			if (element instanceof ItemStack stack) {
				stacks.add(stack);
			}
		}
		return stacks;
	}
}
