package dev.jeasync;

import mezz.jei.common.Internal;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Puts the server's recipe list back onto the client recipe manager before a plugin reads it.
 * <p>
 * JEI's early start used to install the vanilla fallback list over that manager. Any plugin that
 * copies recipes out of the manager during registration then keeps that fallback: Avaritia and its
 * addons take the compressor, smithing, and extreme-crafting lists this way, and an addon that
 * does not name itself after the parent mod is indistinguishable from one that does not read the
 * manager at all. So the restore is done for every plugin phase rather than for a chosen few.
 * <p>
 * Drift is decided on the ids the manager holds, not on their count. A reload can move the manager
 * onto a different list of the same length, and JEI's fallback is exactly that.
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
		Set<ResourceLocation> wanted = new HashSet<>();
		for (Recipe<?> recipe : synced) {
			if (recipe != null) {
				wanted.add(recipe.getId());
			}
		}
		if (managerHasExactly(manager, wanted)) {
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

	/**
	 * Whether the manager holds exactly {@code wanted}. A count alone cannot answer that: the
	 * manager hands out a set of its recipes and the sync is a list, so a fallback list of the same
	 * length is indistinguishable from the real one.
	 */
	private static boolean managerHasExactly(RecipeManager manager, Set<ResourceLocation> wanted) {
		Set<ResourceLocation> present = manager.getRecipeIds().collect(Collectors.toSet());
		return present.equals(wanted);
	}
}
