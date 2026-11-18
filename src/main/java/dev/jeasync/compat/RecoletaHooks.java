package dev.jeasync.compat;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Entry point for releasing Recoleta's caches once JEI has finished loading or has finished
 * rebuilding its search after a resource reload.
 * The startup classes load even when Recoleta is absent, so this type does not name Recoleta classes.
 */
public final class RecoletaHooks {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String RECOLETA = "com.github.recoleta.Recoleta";

	private static final AtomicBoolean warnedIncompatible = new AtomicBoolean();

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
		evictNow("after JEI load");
	}

	/**
	 * Clears Recoleta's caches once the search rebuild a resource reload started has finished.
	 * The rebuild runs on a worker after JEI's reload listener returns, so the completion callback
	 * reports it here rather than clearing at the listener while the rebuild is still allocating.
	 */
	public static void afterResourceReload() {
		if (!present()) {
			return;
		}
		evictNow("after JEI rebuilt its search following a resource reload");
	}

	private static void evictNow(String context) {
		try {
			Class<?> bridge = Class.forName("dev.jeasync.compat.RecoletaBridge");
			Method evict = bridge.getMethod("evict");
			Object result = evict.invoke(null);
			if (result instanceof Integer applied && applied > 0) {
				LOGGER.info("jeasync: evicted Recoleta's intern and soft caches {}", context);
			} else {
				LOGGER.debug("jeasync: Recoleta's cache layout was not recognized, nothing evicted {}", context);
				if (warnedIncompatible.compareAndSet(false, true)) {
					LOGGER.warn("jeasync: Recoleta is present but its cache layout was not recognized; "
						+ "cache eviction is skipped for this version");
				}
			}
		} catch (Throwable t) {
			LOGGER.debug("jeasync: could not evict Recoleta's caches " + context, t);
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
