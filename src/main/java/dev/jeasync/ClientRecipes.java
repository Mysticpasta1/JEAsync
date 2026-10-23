package dev.jeasync;

import mezz.jei.common.Internal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Puts the server's recipe list back onto the client recipe manager before a plugin reads it.
 * <p>
 * JEI's early start used to install the vanilla fallback list over that manager. Avaritia and its
 * addons copy compressor, smithing, and extreme-crafting recipes from the manager during
 * registration, so the fallback list is what they keep.
 */
public final class ClientRecipes {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final ThreadLocal<Integer> IGNORED_READS = ThreadLocal.withInitial(() -> 0);

	private ClientRecipes() {
	}

	/**
	 * The next recipe-manager read on this thread is a presence check, not a plugin taking the list.
	 */
	public static void ignoreNextRecipeRead() {
		IGNORED_READS.set(IGNORED_READS.get() + 1);
	}

	public static boolean ignoringRecipeRead() {
		return IGNORED_READS.get() > 0;
	}

	public static void endIgnoredRecipeRead() {
		int left = IGNORED_READS.get();
		if (left > 0) {
			IGNORED_READS.set(left - 1);
		}
	}

	public static void restoreIfDrifted() {
		if (!Internal.hasClientSyncedRecipes()) {
			return;
		}
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return;
		}
		List<Recipe<?>> synced = Internal.getClientSyncedRecipes();
		RecipeManager manager = level.getRecipeManager();
		if (manager.getRecipes().size() == synced.size()) {
			return;
		}
		Map<ResourceLocation, Recipe<?>> unique = new LinkedHashMap<>();
		for (Recipe<?> recipe : synced) {
			if (recipe != null) {
				unique.put(recipe.getId(), recipe);
			}
		}
		LOGGER.info("jeasync: restoring {} synced recipes before plugin registration", unique.size());
		manager.replaceRecipes(unique.values());
	}
}
