package dev.jeiasync;

/**
 * Runtime switches for the add-on.
 * <p>
 * Deliberately system properties rather than a config file: these are diagnostic escapes for
 * diagnosing a plugin that turns out not to be thread-safe, and a broken startup is not the time
 * to be reading a config parser stack trace. Values are read once at class initialization.
 */
public final class AsyncConfig {
	private static final String ENABLED_PROPERTY = "jeiasync.asyncLoading";
	private static final String THREADS_PROPERTY = "jeiasync.threads";
	private static final String OPT_IN_ONLY_PROPERTY = "jeiasync.requireOptIn";

	private static final boolean ENABLED = readBoolean(ENABLED_PROPERTY, true);
	private static final boolean OPT_IN_ONLY = readBoolean(OPT_IN_ONLY_PROPERTY, false);
	private static final int THREADS = readThreads();

	private AsyncConfig() {
	}

	public static boolean isAsyncLoadingEnabled() {
		return ENABLED;
	}

	/**
	 * When true, only plugins implementing {@code IAsyncCompatiblePlugin} run off-thread, which is
	 * the previous opt-in-only behaviour. Kept as a diagnostic escape for a pack where a plugin
	 * corrupts shared state without throwing anything this add-on can catch.
	 */
	public static boolean isOptInOnly() {
		return OPT_IN_ONLY;
	}

	/**
	 * The JEI version range this jar declares, read from the jar manifest
	 * ({@code Specification-Version}). Useful when reading a bug report.
	 * <p>
	 * Deliberately not {@code getImplementationVersion()}: ForgeGradle writes the
	 * project version there, which is this add-on's version rather than JEI's.
	 */
	public static String getTargetJeiVersion() {
		String range = AsyncConfig.class.getPackage().getSpecificationVersion();
		// Absent in a dev run, where classes come from a directory rather than a jar and so
		// there is no manifest to read.
		return range != null ? range : "unknown (dev run)";
	}

	public static int getThreadCount() {
		return THREADS;
	}

	private static boolean readBoolean(String key, boolean fallback) {
		String value = System.getProperty(key);
		if (value == null) {
			return fallback;
		}
		return Boolean.parseBoolean(value.trim());
	}

	private static int readThreads() {
		String value = System.getProperty(THREADS_PROPERTY);
		if (value != null) {
			try {
				int parsed = Integer.parseInt(value.trim());
				if (parsed > 0) {
					return parsed;
				}
			} catch (NumberFormatException ignored) {
				// fall through to the default
			}
		}
		// Plugin registration is CPU-bound, so sizing to the CPU count keeps the pool small
		// while still using every core. A cached pool would create one thread per concurrently
		// submitted plugin, which in a large pack means hundreds of threads.
		return Math.max(2, Runtime.getRuntime().availableProcessors());
	}
}