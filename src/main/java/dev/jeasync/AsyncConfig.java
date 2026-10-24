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
	private static final String DEFER_FOCUS_LINKS_PROPERTY = "jeasync.deferFocusLinks";
	private static final String SKIP_ANVIL_ENCHANT_RECIPES_PROPERTY = "jeasync.skipAnvilEnchantRecipes";
	private static final String SKIP_ANVIL_REPAIR_RECIPES_PROPERTY = "jeasync.skipAnvilRepairRecipes";
	private static final String SKIPPED_RECIPE_SERIALIZERS_PROPERTY = "jeasync.skippedRecipeSerializers";
	private static final String DEDUP_RECIPE_SERIALIZERS_PROPERTY = "jeasync.dedupRecipeSerializers";
	private static final String FILTER_EMPTY_RECIPES_PROPERTY = "jeasync.filterEmptyRecipes";
	private static final String SKIPPED_CATEGORIES_PROPERTY = "jeasync.skippedCategories";

	/**
	 * Serializer ids whose recipes are dropped from JEI's recipe tables entirely. Empty by default:
	 * Tinkers' Construct's {@code tipped_tool_transform} is now collapsed to one recipe per effect by
	 * {@link #DEFAULT_DEDUP_RECIPE_SERIALIZERS} rather than dropped whole.
	 */
	public static final List<String> DEFAULT_SKIPPED_RECIPE_SERIALIZERS = List.of();

	/**
	 * Serializer ids whose recipes are kept but collapsed to one per effect. Tinkers' Construct's
	 * {@code tipped_tool_transform} is a datapack recipe written one per tool layout and material,
	 * so a modded pack carries dozens to hundreds sharing a handful of tipped effects; this keeps one
	 * recipe per effect and drops the duplicate copies, the way the anvil keeps its repair list and
	 * drops only the redundant enchantment copies. The effect is read from whichever modifier-,
	 * enchantment- or affix-typed field the recipe class declares, so Apothic and Apotheosis affix
	 * serializers added to the list collapse the same way.
	 */
	public static final List<String> DEFAULT_DEDUP_RECIPE_SERIALIZERS = List.of("tconstruct:tipped_tool_transform");

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
	 * When true, recipe registration stores no focus links; the links of a recipe are built at its
	 * first visibility query and cached.
	 */
	public static boolean deferFocusLinks() {
		return resolveBoolean(DEFER_FOCUS_LINKS_PROPERTY, JeAsyncConfig.COMMON.deferFocusLinks, true);
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
	 * When true (the default), a recipe whose supplier holds no ingredients in any role is not
	 * registered: JEI cannot hold air, so those rows would show an empty picture that no focus
	 * can reach. An empty output role by itself does not drop the recipe.
	 */
	public static boolean filterEmptyRecipes() {
		return resolveBoolean(FILTER_EMPTY_RECIPES_PROPERTY, JeAsyncConfig.COMMON.filterEmptyRecipes, true);
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

	/**
	 * Recipe serializer ids whose JEI recipes are kept but collapsed to one per effect. A {@code -D}
	 * property carries a comma-separated list.
	 */
	public static List<String> dedupRecipeSerializers() {
		String property = System.getProperty(DEDUP_RECIPE_SERIALIZERS_PROPERTY);
		if (property != null) {
			return Arrays.stream(property.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		}
		try {
			List<? extends String> configured = JeAsyncConfig.COMMON.dedupRecipeSerializers.get();
			return configured == null ? DEFAULT_DEDUP_RECIPE_SERIALIZERS : List.copyOf(configured);
		} catch (IllegalStateException ignored) {
			// Config is not loaded yet in a dev run; fall back to the default.
			return DEFAULT_DEDUP_RECIPE_SERIALIZERS;
		}
	}

	/**
	 * JEI recipe category ids whose registration is dropped entirely. A {@code -D} property carries
	 * a comma-separated list.
	 */
	public static List<String> skippedCategories() {
		String property = System.getProperty(SKIPPED_CATEGORIES_PROPERTY);
		if (property != null) {
			return Arrays.stream(property.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
		}
		try {
			List<? extends String> configured = JeAsyncConfig.COMMON.skippedCategories.get();
			return configured == null ? List.of() : List.copyOf(configured);
		} catch (IllegalStateException ignored) {
			// Config is not loaded yet in a dev run; fall back to the default.
			return List.of();
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
