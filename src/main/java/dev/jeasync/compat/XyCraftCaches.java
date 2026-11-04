package dev.jeasync.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import dev.jeasync.AsyncConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

/**
 * Releases the rule indexes XyCraft's recipe-viewer integration builds.
 * <p>
 * XyCraft's JEI plugin maps every extractor rule to the block states it matches, then derives the
 * item list or fluid list that rule should display. The block-state map is an intermediate: the
 * plugin only writes it, reads its size for a debug line, and never looks a rule up in it again.
 * On a large pack it holds the full cross product of rules and matching states - about 87 MB in the
 * heap that motivated this class.
 * <p>
 * The item lists are retained because {@code ExtractorCategory} reads them while building a recipe
 * layout. They are derived one stack per matching block state, so multi-state blocks contribute the
 * same item many times. Collapsing those duplicates keeps the display identical because the stacks
 * are indistinguishable.
 * <p>
 * Everything is reflective and guarded: XyCraft is optional, its package moved between versions,
 * and a missing class or field must leave the game untouched. The plugin mixin calls this as soon as
 * XyCraft finishes registering, and the post-load pass calls it again so a recipe reload is covered
 * even when the plugin class moved.
 */
public final class XyCraftCaches {
	private static final Logger LOGGER = LogManager.getLogger();

	private static final String[] PLUGIN_CLASSES = {
		// 1.20.1 line.
		"tv.soaryn.xycraft.machines.compat.jei.JeiPlugin",
		// Newer line, where the same maps moved out of the JEI plugin so EMI can share them.
		"tv.soaryn.xycraft.machines.compat.viewers.ExtractorViewerCommon"
	};

	private static volatile boolean logged;

	private XyCraftCaches() {
	}

	public static void release() {
		try {
			if (!AsyncConfig.optimizeXycraft()) {
				return;
			}
			int blockStateRefs = 0;
			int duplicateItems = 0;
			for (String className : PLUGIN_CLASSES) {
				Class<?> plugin = find(className);
				if (plugin == null) {
					continue;
				}
				blockStateRefs += clearRuleBlockStates(plugin);
				duplicateItems += dedupeRuleItems(plugin);
			}
			if ((blockStateRefs == 0 && duplicateItems == 0) || logged) {
				return;
			}
			logged = true;
			LOGGER.info(
				"jeasync: released {} XyCraft rule block-state references and {} duplicate rule item stacks",
				blockStateRefs,
				duplicateItems
			);
		} catch (RuntimeException | LinkageError e) {
			// XyCraft is optional; a shape change must not fail JEI startup.
			LOGGER.debug("jeasync: could not release XyCraft's rule caches", e);
		}
	}

	@Nullable
	private static Class<?> find(String className) {
		try {
			return Class.forName(className, false, XyCraftCaches.class.getClassLoader());
		} catch (ClassNotFoundException | LinkageError | RuntimeException ignored) {
			// XyCraft is absent, or that version does not have this class.
			return null;
		}
	}

	private static int clearRuleBlockStates(Class<?> plugin) {
		try {
			if (!(staticField(plugin, "RuleToBlockState") instanceof Map<?, ?> ruleToBlockState)
				|| ruleToBlockState.isEmpty()) {
				return 0;
			}
			int references = 0;
			for (Object value : ruleToBlockState.values()) {
				if (value instanceof Collection<?> states) {
					references += states.size();
				}
			}
			ruleToBlockState.clear();
			return references;
		} catch (RuntimeException e) {
			LOGGER.debug("jeasync: could not release XyCraft's rule block-state map", e);
			return 0;
		}
	}

	@SuppressWarnings("unchecked")
	private static int dedupeRuleItems(Class<?> plugin) {
		try {
			if (!(staticField(plugin, "RuleToItems") instanceof Map<?, ?> rawMap) || rawMap.isEmpty()) {
				return 0;
			}
			Map<Object, Object> ruleToItems = (Map<Object, Object>) rawMap;
			int[] removed = {0};
			ruleToItems.replaceAll((rule, value) -> {
				if (!(value instanceof List<?> list) || list.size() < 2) {
					return value;
				}
				LinkedHashMap<ItemKey, ItemStack> unique = new LinkedHashMap<>(list.size());
				for (Object element : list) {
					if (!(element instanceof ItemStack stack)) {
						// Not the list shape this version builds: keep it untouched.
						return value;
					}
					unique.putIfAbsent(ItemKey.of(stack), stack);
				}
				if (unique.size() == list.size()) {
					return value;
				}
				removed[0] += list.size() - unique.size();
				return new ArrayList<>(unique.values());
			});
			return removed[0];
		} catch (RuntimeException e) {
			LOGGER.debug("jeasync: could not compact XyCraft's rule item lists", e);
			return 0;
		}
	}

	@Nullable
	private static Object staticField(Class<?> type, String name) {
		try {
			Field field = type.getDeclaredField(name);
			if (!Modifier.isStatic(field.getModifiers())) {
				return null;
			}
			field.setAccessible(true);
			return field.get(null);
		} catch (ReflectiveOperationException | LinkageError | RuntimeException ignored) {
			return null;
		}
	}

	/**
	 * The parts of a stack that make it visually distinct in the extractor ingredient slot.
	 * {@code ItemStack} has identity equality, so it cannot be used as the set key directly.
	 */
	private record ItemKey(Item item, int count, CompoundTag tag) {
		private static ItemKey of(ItemStack stack) {
			return new ItemKey(stack.getItem(), stack.getCount(), stack.getTag());
		}
	}
}
