package dev.jeasync;

import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import org.apache.logging.log4j.LogManager;

import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Remembers tag ingredients while JEI builds anvil and grindstone recipes.
 * <p>
 * MCreator and datapack enchantments rebuild {@code Ingredient.of(tag)} for every item they are
 * asked about. Dissolving the tag builds a stack per match, and the next call builds that list
 * again. The scan keeps one ingredient per tag and tests membership against the items from that
 * one dissolve.
 * <p>
 * A scan is not registered anywhere global. {@link #clear()} moves an epoch forward and a scan
 * wipes itself the next time one of these methods touches it, so a worker thread that never
 * returns to anvil work does not keep its stacks alive for the session through a registry that
 * outlived the load.
 */
public final class AnvilEnchantCache {
	private static final AtomicInteger EPOCH = new AtomicInteger();
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
	 * Marks every scan as stale. Startup calls this when a load begins or ends, including when the
	 * anvil method threw and its return hook did not run. A scan wipes its own stacks the next time
	 * one of these methods touches it, so nothing is held for a thread that never returns to work.
	 */
	public static void clear() {
		EPOCH.incrementAndGet();
	}

	private static Scan live() {
		Scan scan = LOCAL.get();
		scan.sync();
		if (scan.depth <= 0) {
			return null;
		}
		return scan;
	}

	private static void mark() {
		if (marked) {
			return;
		}
		marked = true;
		LogManager.getLogger(AnvilEnchantCache.class).info(
			"jeasync: reusing tag ingredients during anvil and grindstone recipes"
		);
	}

	private static final class Scan {
		private int depth;
		private int epoch;
		private final HashMap<TagKey<Item>, Ingredient> byTag = new HashMap<>();
		private final IdentityHashMap<Ingredient, Set<Item>> tagItems = new IdentityHashMap<>();

		private void wipe() {
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
