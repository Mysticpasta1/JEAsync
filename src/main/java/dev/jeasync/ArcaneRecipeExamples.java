package dev.jeasync;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * One arcane anvil recipe for each recipe type.
 * <p>
 * Iron's Spells is not on the compile classpath. The recipe maker is rewritten to call
 * {@link #oneOfEach}, and the recipe class is rewritten so drawing one recipe does not walk every
 * spell and every level. Scroll upgrade and item upgrade were already one pair per object. Imbue
 * and affinity attune were not: one object listed every enabled spell.
 * <p>
 * Mysticpasta1 — the arcane anvil category should show the four recipe types, not the cross product.
 */
public final class ArcaneRecipeExamples {
	private static final Logger LOGGER = LogManager.getLogger(ArcaneRecipeExamples.class);
	private static final String RECIPE = "io.redspace.ironsspellbooks.jei.ArcaneAnvilJeiRecipe";
	private static final String SPELL = "io.redspace.ironsspellbooks.api.spells.AbstractSpell";
	private static final String SPELLS = "io.redspace.ironsspellbooks.api.registry.SpellRegistry";
	private static final String ORB = "io.redspace.ironsspellbooks.item.UpgradeOrbTypeData";

	private static volatile boolean marked;
	private static volatile List<?> oneSpell;
	private static volatile Item upgradeOrb;

	private ArcaneRecipeExamples() {
	}

	/**
	 * The list the rewritten maker returns: one scroll upgrade, one imbue, one item upgrade, and
	 * one affinity attune, in the order the original four streams used. A type whose items are
	 * absent is left out. The vanilla factory argument is the maker's first parameter and is unused.
	 */
	public static List<Object> oneOfEach(Object vanillaFactory, Object finder) {
		ArrayList<Object> recipes = new ArrayList<>(4);
		try {
			Class<?> recipe = Class.forName(RECIPE);
			Class<?> spellType = Class.forName(SPELL);
			Object spell = firstSpell();
			if (spell != null) {
				int level = minLevel(spell);
				recipes.add(recipe.getConstructor(spellType, int.class).newInstance(spell, level));
			}
			Item imbue = firstItem(finder, "imbueable");
			if (imbue != null) {
				recipes.add(recipe.getConstructor(Item.class, spellType).newInstance(imbue, spell));
			}
			Item target = firstItem(finder, "upgradeable");
			Item orb = upgradeOrb();
			if (target != null && orb != null) {
				Constructor<?> upgrade = recipe.getConstructor(Item.class, Item.class);
				recipes.add(upgrade.newInstance(target, orb));
			}
			if (spell != null) {
				recipes.add(recipe.getConstructor(spellType).newInstance(spell));
			}
		} catch (Throwable failure) {
			LOGGER.error("jeasync: arcane anvil examples were not built", failure);
			return List.of();
		}
		if (!marked) {
			marked = true;
			MixinApplied.mark("compat.ArcaneAnvilRecipeMaker");
			LOGGER.info("jeasync: arcane anvil shows {} recipe(s), one of each type", recipes.size());
		}
		return recipes;
	}

	/**
	 * The spell list an imbue or affinity recipe walks while it is drawn. One spell, so the slot
	 * shows one stack of that type.
	 */
	public static List<?> oneSpell() {
		List<?> cached = oneSpell;
		if (cached != null) {
			return cached;
		}
		Object spell = firstSpell();
		List<?> list = spell == null ? List.of() : List.of(spell);
		oneSpell = list;
		return list;
	}

	/**
	 * The level range an imbue or affinity recipe walks. The first level is the example.
	 */
	public static IntStream oneLevel(int min, int max) {
		if (max < min) {
			return IntStream.empty();
		}
		return IntStream.of(min);
	}

	private static Object firstSpell() {
		List<?> cached = oneSpell;
		if (cached != null) {
			return cached.isEmpty() ? null : cached.get(0);
		}
		try {
			Method enabled = Class.forName(SPELLS).getMethod("getEnabledSpells");
			Object result = enabled.invoke(null);
			if (result instanceof List<?> spells) {
				for (Object spell : spells) {
					if (spell == null) {
						continue;
					}
					String name = spell.getClass().getName();
					if (name.endsWith("NoneSpell")) {
						continue;
					}
					return spell;
				}
			}
		} catch (ReflectiveOperationException failure) {
			LOGGER.error("jeasync: arcane anvil could not read the spell list", failure);
		}
		return null;
	}

	private static int minLevel(Object spell) {
		try {
			Object level = spell.getClass().getMethod("getMinLevel").invoke(spell);
			if (level instanceof Integer value && value > 0) {
				return value;
			}
		} catch (ReflectiveOperationException failure) {
			LOGGER.error("jeasync: arcane anvil could not read a spell level", failure);
		}
		return 1;
	}

	private static Item firstItem(Object finder, String fieldName) {
		if (finder == null) {
			return null;
		}
		try {
			Field field = finder.getClass().getDeclaredField(fieldName);
			field.setAccessible(true);
			Object value = field.get(finder);
			if (value instanceof Iterable<?> items) {
				for (Object item : items) {
					if (item instanceof Item found && found != Items.AIR) {
						return found;
					}
				}
			}
		} catch (ReflectiveOperationException failure) {
			LOGGER.error("jeasync: arcane anvil could not read {}", fieldName, failure);
		}
		return null;
	}

	private static Item upgradeOrb() {
		Item cached = upgradeOrb;
		if (cached != null) {
			return cached == Items.AIR ? null : cached;
		}
		try {
			Method has = Class.forName(ORB).getMethod("has", ItemStack.class);
			for (Item item : BuiltInRegistries.ITEM) {
				if (item == Items.AIR) {
					continue;
				}
				Object present = has.invoke(null, new ItemStack(item));
				if (Boolean.TRUE.equals(present)) {
					upgradeOrb = item;
					return item;
				}
			}
		} catch (ReflectiveOperationException failure) {
			LOGGER.error("jeasync: arcane anvil could not find an upgrade orb", failure);
		}
		upgradeOrb = Items.AIR;
		return null;
	}
}
