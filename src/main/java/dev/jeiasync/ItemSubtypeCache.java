package dev.jeiasync;

import mezz.jei.api.constants.VanillaTypes;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.runtime.IIngredientManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers which items have a JEI subtype interpreter.
 * <p>
 * {@code hasSubtypes} only looks at the item, but the helper demands an {@link ItemStack} to say
 * so. Recipe registration asks once per ingredient. Caching the answer means a plain item does
 * not allocate a stack just to learn that its recipe id is the item itself.
 * <p>
 * Cleared at the start of each JEI load. Interpreters are registered again before recipes, and a
 * stale "no subtypes" answer would merge different nbt variants into one recipe row.
 */
public final class ItemSubtypeCache {
	private static final ConcurrentHashMap<Item, Boolean> HAS_SUBTYPES = new ConcurrentHashMap<>();

	private ItemSubtypeCache() {
	}

	public static void clear() {
		HAS_SUBTYPES.clear();
	}

	public static boolean hasSubtypes(IIngredientManager manager, Item item) {
		Boolean cached = HAS_SUBTYPES.get(item);
		if (cached != null) {
			return cached;
		}
		IIngredientHelper<ItemStack> helper = manager.getIngredientHelper(VanillaTypes.ITEM_STACK);
		boolean has = helper.hasSubtypes(new ItemStack(item));
		Boolean previous = HAS_SUBTYPES.putIfAbsent(item, has);
		return previous != null ? previous : has;
	}
}
