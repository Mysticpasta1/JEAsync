package dev.jeasync;

import java.util.Arrays;
import java.util.List;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Effective settings. A {@code -Djeasync.*} property wins over {@link JeAsyncConfig} so a benchmark
 * run is not overridden by a config file.
 * <p>
 * Read on every call. Forge loads the file after mixins may already have initialized this class, so
 * a value captured in a static final would stay at the spec default for the session.
 */
public final class AsyncConfig {
	private static final String THREADS_PROPERTY = "jeasync.threads";
	private static final String ROW_CAPACITY_PROPERTY = "jeasync.recipeRowCapacity";
	private static final String DEFER_FOCUS_LINKS_PROPERTY = "jeasync.deferFocusLinks";
	private static final String GRINDSTONE_DISENCHANT_LEVELS_PROPERTY = "jeasync.grindstoneDisenchantLevels";
	private static final String SKIP_GRINDSTONE_REPAIR_RECIPES_PROPERTY = "jeasync.skipGrindstoneRepairRecipes";
	private static final String SKIP_ANVIL_ENCHANT_RECIPES_PROPERTY = "jeasync.skipAnvilEnchantRecipes";
	private static final String SKIP_ANVIL_REPAIR_RECIPES_PROPERTY = "jeasync.skipAnvilRepairRecipes";
	private static final String SKIPPED_RECIPE_SERIALIZERS_PROPERTY = "jeasync.skippedRecipeSerializers";

	/**
	 * The serializer ids kept out of JEI's recipe tables by default. Tinkers' Construct's
	 * {@code tipped_tool_transform} table is a datapack recipe: packs and add-ons write one per
	 * tool layout, and every one registers now that JEI protects the checks from failing.
	 */
	public static final List<String> DEFAULT_SKIPPED_RECIPE_SERIALIZERS = List.of("tconstruct:tipped_tool_transform");

	private AsyncConfig() {
	}

	/**
	 * The JEI version range this jar declares ({@code Specification-Version}). ForgeGradle writes
	 * this add-on's own version into {@code Implementation-Version}, so that one is not used here.
	 * Absent in a dev run, where there is no jar manifest.
	 */
	public static String getTargetJeiVersion() {
		String range = AsyncConfig.class.getPackage().getSpecificationVersion();
		return range != null ? range : "unknown (dev run)";
	}

	public static int getThreadCount() {
		int configured = resolveInt(THREADS_PROPERTY, JeAsyncConfig.COMMON.threads, 0);
		if (configured <= 0) {
			return Math.max(2, Runtime.getRuntime().availableProcessors());
		}
		return configured;
	}

	/**
	 * Initial capacity for JEI's per-ingredient-type index rows. A value of 1 matches upstream.
	 */
	public static int getRecipeRowCapacity() {
		return resolveInt(ROW_CAPACITY_PROPERTY, JeAsyncConfig.COMMON.recipeRowCapacity, 1);
	}

	/**
	 * When true, recipe registration stores no focus links; the links of a recipe are built at its
	 * first visibility query and cached.
	 */
	public static boolean deferFocusLinks() {
		return resolveBoolean(DEFER_FOCUS_LINKS_PROPERTY, JeAsyncConfig.COMMON.deferFocusLinks, true);
	}

	/**
	 * Level cap for the grindstone disenchant recipes. 0 (the default) removes them, -1 matches JEI
	 * (all levels up to 10), 1 keeps one recipe per enchantment.
	 */
	public static int grindstoneDisenchantLevels() {
		return resolveInt(GRINDSTONE_DISENCHANT_LEVELS_PROPERTY, JeAsyncConfig.COMMON.grindstoneDisenchantLevels, 0);
	}

	/** When true (the default), the small grindstone repair-recipe set is skipped. */
	public static boolean skipGrindstoneRepairRecipes() {
		return resolveBoolean(SKIP_GRINDSTONE_REPAIR_RECIPES_PROPERTY, JeAsyncConfig.COMMON.skipGrindstoneRepairRecipes, true);
	}

	/**
	 * When true (the default), the anvil book-enchantment recipes that pair every registered
	 * ingredient with every enchantment and level are skipped.
	 */
	public static boolean skipAnvilEnchantRecipes() {
		return resolveBoolean(SKIP_ANVIL_ENCHANT_RECIPES_PROPERTY, JeAsyncConfig.COMMON.skipAnvilEnchantRecipes, true);
	}

	/** When true (the default), the small anvil repair-recipe set is skipped. */
	public static boolean skipAnvilRepairRecipes() {
		return resolveBoolean(SKIP_ANVIL_REPAIR_RECIPES_PROPERTY, JeAsyncConfig.COMMON.skipAnvilRepairRecipes, true);
	}

	/**
	 * Recipe serializer ids whose JEI recipes are dropped at registration. A {@code -D} property
	 * carries a comma-separated list.
	 */
	public static List<String> skippedRecipeSerializers() {
		String property = System.getProperty(SKIPPED_RECIPE_SERIALIZERS_PROPERTY);
		if (property != null) {
			return Arrays.stream(property.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		}
		try {
			List<? extends String> configured = JeAsyncConfig.COMMON.skippedRecipeSerializers.get();
			return configured == null ? DEFAULT_SKIPPED_RECIPE_SERIALIZERS : List.copyOf(configured);
		} catch (IllegalStateException ignored) {
			// Config is not loaded yet in a dev run; fall back to the default.
			return DEFAULT_SKIPPED_RECIPE_SERIALIZERS;
		}
	}

	private static boolean resolveBoolean(String key, ForgeConfigSpec.BooleanValue value, boolean fallback) {
		String property = System.getProperty(key);
		if (property != null) {
			return Boolean.parseBoolean(property.trim());
		}
		try {
			return value.get();
		} catch (IllegalStateException ignored) {
			// Config is not loaded yet in a dev run; fall back to the default.
			return fallback;
		}
	}

	private static int resolveInt(String key, ForgeConfigSpec.IntValue value, int minimum) {
		String property = System.getProperty(key);
		if (property != null) {
			try {
				int parsed = Integer.parseInt(property.trim());
				if (parsed >= minimum) {
					return parsed;
				}
			} catch (NumberFormatException ignored) {
				// Fall through to the config file.
			}
		}
		try {
			return value.get();
		} catch (IllegalStateException ignored) {
			// Config is not loaded yet in a dev run; fall back to the default.
			return minimum;
		}
	}
}
