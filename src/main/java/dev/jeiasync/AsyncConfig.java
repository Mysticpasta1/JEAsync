package dev.jeiasync;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Effective settings. A {@code -Djeiasync.*} property wins over {@link JeiAsyncConfig} so a benchmark
 * run is not overridden by a config file.
 * <p>
 * Read on every call. Forge loads the file after mixins may already have initialized this class, so
 * a value captured in a static final would stay at the spec default for the session.
 */
public final class AsyncConfig {
	private static final String THREADS_PROPERTY = "jeiasync.threads";
	private static final String ROW_CAPACITY_PROPERTY = "jeiasync.recipeRowCapacity";

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
		int configured = resolveInt(THREADS_PROPERTY, JeiAsyncConfig.COMMON.threads, 0);
		if (configured <= 0) {
			return Math.max(2, Runtime.getRuntime().availableProcessors());
		}
		return configured;
	}

	/**
	 * Initial capacity for JEI's per-ingredient-type index rows. A value of 1 matches upstream.
	 */
	public static int getRecipeRowCapacity() {
		return resolveInt(ROW_CAPACITY_PROPERTY, JeiAsyncConfig.COMMON.recipeRowCapacity, 1);
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
