package dev.jeiasync;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;

/**
 * One plain stack per item for Iron's Spells recipe registration, plus a stack that already
 * holds a spell when the plain item itself cannot be imbued.
 * <p>
 * The arcane anvil, scroll forge, cauldron, and repair lists classify items. A second copy of the
 * same item with different tags does not add another recipe. Spell containers are the exception:
 * a plain stack of that item would be skipped and the imbued one would be kept.
 * <p>
 * Mysticpasta1 — the finder walked every JEI stack, including ones that classify the same item.
 */
public final class IssItemScan {
	private static final String SPELL_KEY = "irons_spellbooks:spell_container";
	private static final String LEGACY_KEY = "ISB_Spells";
	private static volatile Method canImbue;

	private IssItemScan() {
	}

	public static Collection<ItemStack> representative(Object ingredientManager) {
		IdentityHashMap<Item, ItemStack> plain = new IdentityHashMap<>();
		ArrayList<ItemStack> stacks = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			ItemStack stack = new ItemStack(item);
			plain.put(item, stack);
			stacks.add(stack);
		}
		int extra = 0;
		IdentityHashMap<Item, Boolean> added = new IdentityHashMap<>();
		for (Object value : allStacks(ingredientManager)) {
			if (!(value instanceof ItemStack stack) || stack.isEmpty() || !holdsSpell(stack)) {
				continue;
			}
			Item item = stack.getItem();
			if (added.containsKey(item)) {
				continue;
			}
			ItemStack plainStack = plain.get(item);
			if (plainStack != null && (holdsSpell(plainStack) || canImbue(plainStack))) {
				added.put(item, Boolean.TRUE);
				continue;
			}
			added.put(item, Boolean.TRUE);
			stacks.add(stack);
			extra++;
		}
		LogManager.getLogger(IssItemScan.class).info(
			"JEI Async: arcane anvil classifies {} items and {} spell-container stacks",
			plain.size(),
			extra
		);
		return stacks;
	}

	private static boolean holdsSpell(ItemStack stack) {
		CompoundTag tag = stack.getTag();
		return tag != null && (tag.contains(SPELL_KEY) || tag.contains(LEGACY_KEY));
	}

	private static boolean canImbue(ItemStack stack) {
		Method method = canImbueMethod();
		if (method == null) {
			return false;
		}
		try {
			return (Boolean) method.invoke(null, stack);
		} catch (ReflectiveOperationException failure) {
			return false;
		}
	}

	private static Method canImbueMethod() {
		Method method = canImbue;
		if (method != null) {
			return method;
		}
		try {
			Class<?> utils = Class.forName("io.redspace.ironsspellbooks.api.util.Utils");
			method = utils.getMethod("canImbue", ItemStack.class);
			canImbue = method;
			return method;
		} catch (ReflectiveOperationException failure) {
			return null;
		}
	}

	@SuppressWarnings("unchecked")
	private static Collection<Object> allStacks(Object ingredientManager) {
		if (ingredientManager == null) {
			return java.util.List.of();
		}
		try {
			Method method = ingredientManager.getClass().getMethod("getAllItemStacks");
			Object value = method.invoke(ingredientManager);
			if (value instanceof Collection<?> collection) {
				return (Collection<Object>) collection;
			}
		} catch (ReflectiveOperationException failure) {
			LogManager.getLogger(IssItemScan.class).warn(
				"JEI Async: arcane anvil could not read JEI's item list ({})",
				failure.getClass().getSimpleName()
			);
		}
		return java.util.List.of();
	}
}
