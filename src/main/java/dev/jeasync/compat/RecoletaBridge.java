package dev.jeasync.compat;

import com.github.recoleta.memory.cache.RecoletaCaches;
import com.github.recoleta.memory.cache.RecoletaInterns;
import com.github.recoleta.memory.cache.WeakInternTable;
import com.github.recoleta.memory.gc.IncrementalCleaner;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Direct calls into Recoleta 0.2.1.
 * {@link RecoletaHooks} loads this type only when Recoleta is installed.
 */
public final class RecoletaBridge {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Field STORE = findStoreField();

	private RecoletaBridge() {
	}

	/**
	 * Replaces both weak intern tables' backing maps and invalidates all shared soft caches.
	 * The calls go straight to the holders, so a pressure pass that already ran during
	 * JEI's load cannot suppress this eviction.
	 * <p>
	 * {@code clear()} only nulls map entries; the {@code ConcurrentHashMap} keeps its grown
	 * {@code Node[]} table, which is tens of megabytes after JEI's load. A fresh map drops
	 * that array. {@code drainAll} then releases the reference queue, whose collected entries
	 * the tick cleaner polls only 64 at a time.
	 */
	public static void evictAfterJeiLoad() {
		replaceStore(RecoletaInterns.STRINGS);
		replaceStore(RecoletaInterns.STYLES);
		IncrementalCleaner.drainAll();
		RecoletaCaches.RL_TO_STRING.invalidateAll();
		RecoletaCaches.LITERAL_CONTENTS.invalidateAll();
		RecoletaCaches.TRANSLATABLE_CONTENTS.invalidateAll();
		RecoletaCaches.KEYBIND_CONTENTS.invalidateAll();
		RecoletaCaches.SCORE_CONTENTS.invalidateAll();
		RecoletaCaches.SELECTOR_CONTENTS.invalidateAll();
		RecoletaCaches.NBT_CONTENTS.invalidateAll();
	}

	private static void replaceStore(WeakInternTable<?> table) {
		if (STORE != null) {
			try {
				STORE.set(table, new ConcurrentHashMap<>());
				return;
			} catch (Throwable t) {
				LOGGER.warn("jeasync: could not replace Recoleta's intern table map", t);
			}
		}
		table.clear();
	}

	private static Field findStoreField() {
		try {
			Field field = WeakInternTable.class.getDeclaredField("store");
			field.setAccessible(true);
			return field;
		} catch (Throwable t) {
			LOGGER.warn("jeasync: could not access Recoleta's intern table map", t);
			return null;
		}
	}
}
