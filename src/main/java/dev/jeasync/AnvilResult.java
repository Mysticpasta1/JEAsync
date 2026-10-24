package dev.jeasync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import dev.jeasync.mixin.nbt.CapabilityProviderAccess;
import dev.jeasync.mixin.nbt.ItemStackTagAccess;

import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;

/**
 * Builds the anvil output JEI shows for a book or a repair.
 * <p>
 * The menu posts an update for every pair, and each listener then handles that result. A plain
 * book applies its enchantment, and a repair restores durability. The stack keeps the enchantment
 * when the level cost is high, which is the result this pack's anvil already keeps.
 * <p>
 * Mysticpasta1 — recipe registration was spending the plugin thread inside anvil listeners.
 */
public final class AnvilResult {
	private static final ThreadLocal<IdentityHashMap<ItemStack, CompoundTag>> CAPS_STORE =
		ThreadLocal.withInitial(IdentityHashMap::new);

	private AnvilResult() {
	}

	/**
	 * Drops this thread's caps store. A pooled worker keeps whatever the last scan left behind, and
	 * an emptied {@link IdentityHashMap} still holds its backing table.
	 */
	public static void clearScanCaches() {
		CAPS_STORE.remove();
	}

	public static ItemStack combine(ItemStack left, ItemStack right) {
		if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
			return ItemStack.EMPTY;
		}
		if (right.is(Items.ENCHANTED_BOOK)) {
			return applyBook(left, right);
		}
		if (left.isDamageableItem() && left.getItem().isValidRepairItem(left, right)) {
			return repairWithMaterial(left, right);
		}
		if (left.isDamageableItem() && left.is(right.getItem())) {
			return repairWithTwin(left, right);
		}
		return ItemStack.EMPTY;
	}

	private static ItemStack applyBook(ItemStack left, ItemStack book) {
		Map<Enchantment, Integer> adding = EnchantmentHelper.getEnchantments(book);
		if (adding.isEmpty()) {
			return ItemStack.EMPTY;
		}
		Map<Enchantment, Integer> have = new HashMap<>(EnchantmentHelper.getEnchantments(left));
		boolean applied = false;
		for (Map.Entry<Enchantment, Integer> entry : adding.entrySet()) {
			Enchantment enchantment = entry.getKey();
			if (enchantment == null) {
				continue;
			}
			int level = entry.getValue();
			int existing = have.getOrDefault(enchantment, 0);
			int next = existing == level ? level + 1 : Math.max(level, existing);
			int max = enchantment.getMaxLevel();
			if (next > max) {
				next = max;
			}
			if (next <= 0 || !fits(enchantment, left, have)) {
				continue;
			}
			have.put(enchantment, next);
			applied = true;
		}
		if (!applied) {
			return ItemStack.EMPTY;
		}
		ItemStack output = buildOutput(left);
		EnchantmentHelper.setEnchantments(have, output);
		output.setRepairCost(AnvilMenu.calculateIncreasedRepairCost(Math.max(left.getBaseRepairCost(), book.getBaseRepairCost())));
		return output;
	}

	/**
	 * Builds the stack a book result produces without copying the tag content it never changes: a
	 * fresh top-level compound whose children are the ingredient's own tag entries by reference. A
	 * book result only replaces Enchantments and RepairCost, and both writes go into the fresh
	 * compound, so the shared children stay untouched. Display stacks are read, never written.
	 */
	private static ItemStack buildOutput(ItemStack left) {
		ItemStack output = new ItemStack(left.getItem(), left.getCount(), caps(left));
		output.setPopTime(left.getPopTime());
		CompoundTag source = ((ItemStackTagAccess) (Object) left).jeasync$getTag();
		if (source != null) {
			CompoundTag rebuilt = new CompoundTag();
			for (String key : source.getAllKeys()) {
				Tag value = source.get(key);
				if (value != null) {
					rebuilt.put(key, value);
				}
			}
			((ItemStackTagAccess) (Object) output).jeasync$setTag(rebuilt);
		}
		return output;
	}

	private static CompoundTag caps(ItemStack left) {
		IdentityHashMap<ItemStack, CompoundTag> store = CAPS_STORE.get();
		if (store.containsKey(left)) {
			return store.get(left);
		}
		CompoundTag caps = ((CapabilityProviderAccess) (Object) left).jeasync$serializeCaps();
		store.put(left, caps);
		return caps;
	}

	/**
	 * A plain item has already been accepted for this book. Compatibility only matters once the
	 * item already holds an enchantment.
	 */
	private static boolean fits(Enchantment enchantment, ItemStack left, Map<Enchantment, Integer> have) {
		if (have.isEmpty()) {
			return true;
		}
		if (!enchantment.canEnchant(left) && !left.is(Items.ENCHANTED_BOOK)) {
			return false;
		}
		for (Enchantment other : have.keySet()) {
			if (other != enchantment && !enchantment.isCompatibleWith(other)) {
				return false;
			}
		}
		return true;
	}

	private static ItemStack repairWithMaterial(ItemStack left, ItemStack material) {
		int unit = Math.min(left.getDamageValue(), left.getMaxDamage() / 4);
		if (unit <= 0) {
			return ItemStack.EMPTY;
		}
		ItemStack output = left.copy();
		int used = 0;
		int repair = unit;
		while (repair > 0 && used < material.getCount()) {
			output.setDamageValue(output.getDamageValue() - repair);
			used++;
			repair = Math.min(output.getDamageValue(), output.getMaxDamage() / 4);
		}
		if (used == 0) {
			return ItemStack.EMPTY;
		}
		output.setRepairCost(AnvilMenu.calculateIncreasedRepairCost(Math.max(left.getBaseRepairCost(), material.getBaseRepairCost())));
		return output;
	}

	private static ItemStack repairWithTwin(ItemStack left, ItemStack right) {
		int remain = (left.getMaxDamage() - left.getDamageValue()) + (right.getMaxDamage() - right.getDamageValue());
		int bonus = right.getMaxDamage() * 12 / 100;
		int damage = left.getMaxDamage() - (remain + bonus);
		if (damage < 0) {
			damage = 0;
		}
		if (damage >= left.getDamageValue()) {
			return ItemStack.EMPTY;
		}
		ItemStack output = left.copy();
		output.setDamageValue(damage);
		output.setRepairCost(AnvilMenu.calculateIncreasedRepairCost(Math.max(left.getBaseRepairCost(), right.getBaseRepairCost())));
		return output;
	}
}
