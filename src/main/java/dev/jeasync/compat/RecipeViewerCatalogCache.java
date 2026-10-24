package dev.jeasync.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import mezz.jei.api.IModPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Releases the recipe-viewer catalogs that the Sophisticated mods cache on their JEI plugin.
 * <p>
 * Sophisticated Storage in Motion builds its display catalog from its {@code registerAdvanced} and
 * {@code registerVanillaCategoryExtensions} callbacks and keeps it in a field on the plugin
 * instance. Nothing clears that field: it is the plugin's own lazy cache, held for the rest of the
 * session, and a dominator tree of a modded client shows it as one of the largest single owners of
 * retained heap (one run: 155 MB and 14.6 million objects behind one plugin).
 * <p>
 * The catalog is a cache, not a registry. Every JEI query that needs it goes through
 * {@code getCatalog}, which rebuilds it when the field is null. Dropping it once the load has
 * finished therefore loses nothing a player can see; it moves the build cost off startup and onto
 * the first JEI browse of a moving-storage recipe, which is what the heap is spent on either way.
 * <p>
 * Matching is on the field's declared type rather than on the plugin's name or package: the type is
 * the mod's own {@code IRecipeViewerDisplayCatalog} interface, and the same mechanism covers the
 * storage, backpacks and in-motion plugins without naming each one. Absent mods match nothing.
 */
public final class RecipeViewerCatalogCache {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final String CATALOG_TYPE =
		"net.p3pp3rf1y.sophisticatedcore.compat.recipeviewers.common.IRecipeViewerDisplayCatalog";

	private static final AtomicBoolean LOGGED = new AtomicBoolean();

	private static final List<IModPlugin> seenPlugins = new ArrayList<>();

	private RecipeViewerCatalogCache() {
	}

	/**
	 * Remembers the plugins of a phase. JEI already holds these for the session, so the list keeps
	 * nothing alive that would otherwise be collected.
	 */
	public static synchronized void remember(Collection<IModPlugin> plugins) {
		for (IModPlugin plugin : plugins) {
			if (plugin != null && !seenPlugins.contains(plugin)) {
				seenPlugins.add(plugin);
			}
		}
	}

	/**
	 * Nulls every cached catalog field on the plugins seen so far. Called after JEI's load finishes,
	 * when the catalog is no longer needed for registration.
	 */
	public static synchronized void releaseAfterLoad() {
		int released = 0;
		for (IModPlugin plugin : seenPlugins) {
			released += releaseCatalogFields(plugin);
		}
		if (released == 0 || !LOGGED.compareAndSet(false, true)) {
			return;
		}
		LOGGER.info(
			"jeasync: released {} recipe-viewer catalog(s) cached by a mod plugin after load, the first moving-storage query rebuilds them",
			released
		);
	}

	private static int releaseCatalogFields(IModPlugin plugin) {
		int released = 0;
		for (Class<?> type = plugin.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (!CATALOG_TYPE.equals(field.getType().getName()) || Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				try {
					field.setAccessible(true);
					if (field.get(plugin) == null) {
						continue;
					}
					field.set(plugin, null);
					released++;
				} catch (ReflectiveOperationException | RuntimeException | Error e) {
					// Inaccessible or sealed by a security manager: the plugin keeps its catalog.
					LOGGER.debug("jeasync: {} cannot release its cached recipe-viewer catalog", plugin.getClass().getName(), e);
				}
			}
		}
		return released;
	}
}
