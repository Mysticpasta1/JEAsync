package dev.jeiasync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import org.apache.logging.log4j.LogManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remembers enchant categories while JEI builds anvil and grindstone recipes.
 * <p>
 * Both scans ask which categories a tool can take once per enchantment. Tinkers answers that by
 * testing a long list of tool actions. A stack whose tag is empty, or holds only vanilla
 * enchantment lists, has the same answer as the plain item. A stack with material data keeps its
 * own answer. The maps live on the scan thread and are dropped when the scan returns.
 * <p>
 * MCreator enchantments rebuild {@code Ingredient.of(tag)} for every item they are asked about.
 * Dissolving the tag builds a stack per match, and the next call builds that list again. The scan
 * keeps one ingredient per tag and tests membership against the items from that one dissolve.
 * <p>
 * Mysticpasta1 — one plugin thread sat in tool-action checks at registering recipes 370/376.
 * Locating spent the grindstone scan rebuilding sword, axe, pickaxe, and shovel ingredients.
 */
public final class AnvilEnchantCache {
	private static final AtomicInteger EPOCH = new AtomicInteger();
	private static final ConcurrentLinkedQueue<Scan> ALL = new ConcurrentLinkedQueue<>();
	private static final ThreadLocal<Scan> LOCAL = ThreadLocal.withInitial(Scan::new);

	private static volatile boolean marked;

	private AnvilEnchantCache() {
	}

	public static void enter() {
		Scan scan = LOCAL.get();
		scan.sync();
		scan.depth++;
		if (scan.depth == 1) {
			mark();
		}
	}

	public static void exit() {
		Scan scan = LOCAL.get();
		scan.sync();
		if (scan.depth > 0) {
			scan.depth--;
		}
		if (scan.depth == 0) {
			scan.wipe();
		}
	}

	public static boolean active() {
		Scan scan = LOCAL.get();
		scan.sync();
		return scan.depth > 0;
	}

	public static Set<EnchantmentCategory> get(ItemStack stack) {
		Scan scan = LOCAL.get();
		scan.sync();
		if (scan.depth <= 0 || stack == null) {
			return null;
		}
		if (followsItem(stack)) {
			return scan.byItem.get(stack.getItem());
		}
		return scan.categories.get(stack);
	}

	public static void put(ItemStack stack, Set<EnchantmentCategory> categories) {
		Scan scan = LOCAL.get();
		scan.sync();
		if (scan.depth <= 0 || stack == null || categories == null) {
			return;
		}
		if (followsItem(stack)) {
			scan.byItem.put(stack.getItem(), categories);
			return;
		}
		scan.categories.put(stack, categories);
	}

	/**
	 * The ingredient already stored for this tag during the current scan, or null when the scan is
	 * idle or this tag has not been dissolved yet.
	 */
	public static Ingredient tagIngredient(TagKey<Item> tag) {
		Scan scan = live();
		if (scan == null || tag == null) {
			return null;
		}
		return scan.byTag.get(tag);
	}

	/**
	 * Keeps {@code ingredient} as the shared value of {@code tag} and records the items in its
	 * dissolved list. A second call for the same tag leaves the first dissolve in place.
	 */
	public static void keepTagIngredient(TagKey<Item> tag, Ingredient ingredient) {
		Scan scan = live();
		if (scan == null || tag == null || ingredient == null || scan.byTag.containsKey(tag)) {
			return;
		}
		scan.byTag.put(tag, ingredient);
		Set<Item> items = new HashSet<>();
		try {
			for (ItemStack stack : ingredient.getItems()) {
				if (stack != null && !stack.isEmpty()) {
					items.add(stack.getItem());
				}
			}
		} catch (RuntimeException exception) {
			scan.byTag.remove(tag, ingredient);
			throw exception;
		}
		scan.tagItems.put(ingredient, items.isEmpty() ? Set.of() : Set.copyOf(items));
	}

	/**
	 * Whether {@code ingredient} is a tag ingredient kept for this scan and contains {@code item}.
	 * Null means the ingredient is not one of those tags, so the caller uses the original test.
	 */
	public static Boolean tagContains(Ingredient ingredient, Item item) {
		Scan scan = live();
		if (scan == null || ingredient == null || item == null) {
			return null;
		}
		Set<Item> items = scan.tagItems.get(ingredient);
		if (items == null) {
			return null;
		}
		return items.contains(item);
	}

	/**
	 * Drops every scan's stacks. Startup calls this when a load begins or ends, including when the
	 * anvil method threw and its return hook did not run.
	 */
	public static void clear() {
		EPOCH.incrementAndGet();
		int epoch = EPOCH.get();
		for (Scan scan : ALL) {
			scan.depth = 0;
			scan.epoch = epoch;
			scan.wipe();
		}
	}

	/**
	 * Vanilla enchantment lists do not change which categories a tool belongs to. Material tags do.
	 */
	private static Scan live() {
		Scan scan = LOCAL.get();
		scan.sync();
		if (scan.depth <= 0) {
			return null;
		}
		return scan;
	}

	private static boolean followsItem(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		if (tag == null || tag.isEmpty()) {
			return true;
		}
		int count = tag.size();
		if (count > 2) {
			return false;
		}
		boolean enchantments = tag.contains("Enchantments");
		boolean stored = tag.contains("StoredEnchantments");
		if (count == 1) {
			return enchantments || stored;
		}
		return enchantments && stored;
	}

	private static void mark() {
		if (marked) {
			return;
		}
		marked = true;
		LogManager.getLogger(AnvilEnchantCache.class).info(
			"JEI Async: reusing enchant categories for each tool during anvil and grindstone recipes"
		);
	}

	private static final class Scan {
		private int depth;
		private int epoch;
		private final IdentityHashMap<ItemStack, Set<EnchantmentCategory>> categories = new IdentityHashMap<>(1024);
		private final IdentityHashMap<Item, Set<EnchantmentCategory>> byItem = new IdentityHashMap<>(1024);
		private final HashMap<TagKey<Item>, Ingredient> byTag = new HashMap<>();
		private final IdentityHashMap<Ingredient, Set<Item>> tagItems = new IdentityHashMap<>();

		private Scan() {
			ALL.add(this);
		}

		private void wipe() {
			this.categories.clear();
			this.byItem.clear();
			this.byTag.clear();
			this.tagItems.clear();
		}

		private void sync() {
			int current = EPOCH.get();
			if (this.epoch != current) {
				this.epoch = current;
				this.depth = 0;
				wipe();
			}
		}
	}
}
