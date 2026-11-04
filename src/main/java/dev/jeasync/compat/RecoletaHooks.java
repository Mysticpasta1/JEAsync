package dev.jeasync.compat;

import java.lang.reflect.Method;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Entry point for releasing Recoleta's caches once JEI has finished loading.
 * The startup classes load even when Recoleta is absent, so this type does not name Recoleta classes.
 */
public final class RecoletaHooks {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String RECOLETA = "com.github.recoleta.Recoleta";

	private static boolean resolved;
	private static boolean present;

	private RecoletaHooks() {
	}

	/**
	 * Clears Recoleta's weak intern tables and invalidates its shared soft caches. The eviction
	 * replaces the grown intern-table maps and drains their reference queues, so the entries
	 * collected during JEI's load are released in one pass. The caches are addressed directly
	 * so a pressure pass that already ran during JEI's load cannot suppress this eviction.
	 */
	public static void afterJeiLoad() {
		if (!present()) {
			return;
		}
		evictNow();
	}

	private static void evictNow() {
		try {
			Class<?> bridge = Class.forName("dev.jeasync.compat.RecoletaBridge");
			Method evict = bridge.getMethod("evictAfterJeiLoad");
			evict.invoke(null);
			LOGGER.info("jeasync: evicted Recoleta's intern and soft caches after JEI load");
		} catch (Throwable t) {
			LOGGER.warn("jeasync: could not evict Recoleta's caches after JEI load", t);
		}
	}

	private static synchronized boolean present() {
		if (!resolved) {
			resolved = true;
			try {
				Class.forName(RECOLETA, false, RecoletaHooks.class.getClassLoader());
				present = true;
			} catch (ClassNotFoundException notLoaded) {
				present = false;
			} catch (Throwable ignored) {
				present = true;
			}
		}
		return present;
	}
}
