package dev.jeiasync;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Effective settings. A {@code -Djeiasync.*} property wins over {@link JeiAsyncConfig} so a benchmark
 * run is not overridden by a config file.
 * <p>
 * Read on every call. JEI builds its starter from the mod constructor, which is before NeoForge loads
 * this client config, and {@code ConfigValue.get()} throws until then. An unloaded spec contributes
 * its default. A value captured in a static final would stay there for the session, so later calls
 * read the file once it is loaded.
 */
public final class AsyncConfig {
	private static final String THREADS_PROPERTY = "jeiasync.threads";
	private static final String ROW_CAPACITY_PROPERTY = "jeiasync.recipeRowCapacity";

	private AsyncConfig() {
	}

	/**
	 * The JEI version range this jar declares ({@code Specification-Version}). The build writes
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

	private static int resolveInt(String key, ModConfigSpec.IntValue value, int minimum) {
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
		if (!JeiAsyncConfig.SPEC.isLoaded()) {
			return value.getDefault();
		}
		return value.get();
	}
}
