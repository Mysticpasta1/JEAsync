package dev.jeasync.compat;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reflective eviction of Recoleta's shared caches.
 * <p>
 * This type names no Recoleta classes, so a missing Recoleta or a changed cache layout cannot fail
 * linkage here. Each cache, intern table, and cleaner entry point is resolved separately and
 * guarded, so one renamed field skips only that step instead of leaving the others untouched.
 */
public final class RecoletaBridge {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String INTERNS = "com.github.recoleta.memory.cache.RecoletaInterns";
	private static final String CACHES = "com.github.recoleta.memory.cache.RecoletaCaches";
	private static final String CLEANER = "com.github.recoleta.memory.gc.IncrementalCleaner";
	private static final String[] INTERN_TABLES = {"STRINGS", "STYLES"};
	private static final String[] SOFT_CACHES = {
		"RL_TO_STRING",
		"LITERAL_CONTENTS",
		"TRANSLATABLE_CONTENTS",
		"KEYBIND_CONTENTS",
		"SCORE_CONTENTS",
		"SELECTOR_CONTENTS",
		"NBT_CONTENTS"
	};

	private RecoletaBridge() {
	}

	/**
	 * Replaces the weak intern tables' backing maps when the known {@code ConcurrentHashMap}
	 * layout is still present, drains their reference queues, and invalidates every shared soft
	 * cache that still exposes {@code invalidateAll}. Returns the number of steps that ran.
	 */
	public static int evict() {
		int applied = 0;
		for (String table : INTERN_TABLES) {
			if (replaceInternStore(table)) {
				applied++;
			}
		}
		if (drainCleaner()) {
			applied++;
		}
		for (String cache : SOFT_CACHES) {
			if (invalidateCache(cache)) {
				applied++;
			}
		}
		return applied;
	}

	private static boolean replaceInternStore(String fieldName) {
		Object table = staticField(INTERNS, fieldName);
		if (table == null) {
			return false;
		}
		Field store = findField(table.getClass(), "store");
		if (store != null && store.getType() == ConcurrentHashMap.class) {
			try {
				store.set(table, new ConcurrentHashMap<>());
				return true;
			} catch (Throwable t) {
				LOGGER.debug("jeasync: could not replace Recoleta's {} intern map", fieldName, t);
			}
		}
		return invokeNoArg(table, "clear");
	}

	private static boolean drainCleaner() {
		try {
			Class<?> cleaner = Class.forName(CLEANER);
			Method drainAll = cleaner.getMethod("drainAll");
			drainAll.invoke(null);
			return true;
		} catch (Throwable t) {
			LOGGER.debug("jeasync: could not drain Recoleta's reference queues", t);
			return false;
		}
	}

	private static boolean invalidateCache(String fieldName) {
		Object cache = staticField(CACHES, fieldName);
		return cache != null && invokeNoArg(cache, "invalidateAll");
	}

	private static Object staticField(String className, String fieldName) {
		try {
			Field field = findField(Class.forName(className), fieldName);
			return field == null ? null : field.get(null);
		} catch (Throwable t) {
			LOGGER.debug("jeasync: could not read Recoleta's {}.{}", className, fieldName, t);
			return null;
		}
	}

	private static Field findField(Class<?> type, String fieldName) {
		for (Class<?> current = type; current != null; current = current.getSuperclass()) {
			try {
				Field field = current.getDeclaredField(fieldName);
				field.setAccessible(true);
				return field;
			} catch (NoSuchFieldException ignored) {
				// Try the superclass.
			} catch (Throwable t) {
				LOGGER.debug("jeasync: could not access Recoleta's {} field", fieldName, t);
				return null;
			}
		}
		return null;
	}

	private static boolean invokeNoArg(Object target, String methodName) {
		try {
			Method method = target.getClass().getMethod(methodName);
			method.invoke(target);
			return true;
		} catch (Throwable t) {
			LOGGER.debug("jeasync: could not call Recoleta's {}", methodName, t);
			return false;
		}
	}
}
