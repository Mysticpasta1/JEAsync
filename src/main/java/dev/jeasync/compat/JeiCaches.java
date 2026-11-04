package dev.jeasync.compat;

import java.lang.reflect.Field;

import com.google.common.cache.LoadingCache;
import mezz.jei.common.ingredients.itemStacks.TypedItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Releases JEI's transient typed-item-stack cache once loading is over.
 * <p>
 * {@code TypedItemStack.getIngredient()} keeps every stack it creates in a one-second
 * access cache. The cache is keyed by the wrapper itself, so equal items do not share an entry.
 * Registration and the deferred search build create a wrapper per slot and read it once, which
 * means the cache rarely hits but still holds a second or more of every expanded ingredient.
 * JEI lets entries expire after a second, but a heap dump taken shortly after startup showed the
 * cache alone retaining about 58 MB.
 * <p>
 * Dropping it after the load costs nothing: the cache is an optimisation, not a registry, and a
 * later lookup creates the stack again. This is client-side cleanup and JEI is a hard dependency of
 * this mod, so the class is always present.
 */
public final class JeiCaches {
	private static final Logger LOGGER = LogManager.getLogger();

	private static volatile boolean logged;

	private JeiCaches() {
	}

	public static void clearTypedItemStackCache() {
		try {
			Field cacheField = TypedItemStack.class.getDeclaredField("CACHE");
			cacheField.setAccessible(true);
			if (!(cacheField.get(null) instanceof LoadingCache<?, ?> cache)) {
				return;
			}
			long size = cache.size();
			cache.invalidateAll();
			cache.cleanUp();
			if (size > 0 && !logged) {
				logged = true;
				LOGGER.info("jeasync: released {} typed item stacks cached after load", size);
			}
		} catch (ReflectiveOperationException | LinkageError | RuntimeException e) {
			LOGGER.debug("jeasync: could not clear the typed item stack cache", e);
		}
	}
}
