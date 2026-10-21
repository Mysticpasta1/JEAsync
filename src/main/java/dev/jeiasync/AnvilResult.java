package dev.jeiasync;

import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

import java.util.HashMap;
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
	private AnvilResult() {
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
		ItemStack output = left.copy();
		Map<Enchantment, Integer> have = new HashMap<>(EnchantmentHelper.getEnchantments(output));
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
		EnchantmentHelper.setEnchantments(have, output);
		output.setRepairCost(AnvilMenu.calculateIncreasedRepairCost(Math.max(left.getBaseRepairCost(), book.getBaseRepairCost())));
		return output;
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
