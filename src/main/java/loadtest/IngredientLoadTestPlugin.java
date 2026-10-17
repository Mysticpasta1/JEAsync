package loadtest;

import mezz.jei.api.JeiPlugin;
import mezz.jei.api.registration.IModIngredientRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Registers a large synthetic ingredient type so that JEI's search index build has a measurable
 * amount of work to do.
 * <p>
 * Deliberately lives in the same source set as the CPU-work plugins, and is a plain
 * {@code IModPlugin} rather than an opt-in one, so it is registered on the main thread and cannot be
 * confused with the async measurement runs.
 * <p>
 * It logs from both {@code registerRecipes} and {@code registerIngredients}. The recipes hook is
 * one that is known to be reached for every plugin in this source set, so a single run shows
 * whether the class is discovered at all, and if so whether the ingredient registration is the part
 * that is failing.
 */
@JeiPlugin
public class IngredientLoadTestPlugin implements mezz.jei.api.IModPlugin {
	private static final Logger LOGGER = LogManager.getLogger();

	private static final int COUNT = Integer.getInteger("loadtest.ingredients", 4000);
	private static final int BLOCKS = Integer.getInteger("loadtest.ingredientBlocks", 1);

	@Override
	public ResourceLocation getPluginUid() {
		return new ResourceLocation("loadtest", "ingredients");
	}

	@Override
	public void registerRecipes(IRecipeRegistration registration) {
		LOGGER.info("[loadtest] IngredientLoadTestPlugin reached registerRecipes, target {} ingredients", COUNT);
	}

	@Override
	public void registerIngredients(IModIngredientRegistration registration) {
		int perBlock = Math.max(1, COUNT / BLOCKS);
		long start = System.nanoTime();
		for (int block = 0; block < BLOCKS; block++) {
			registration.register(
				LoadTestIngredient.TYPE,
				LoadTestIngredient.create(perBlock, block * perBlock),
				LoadTestIngredient.HELPER,
				LoadTestIngredient.RENDERER
			);
		}
		LOGGER.info("[loadtest] registered {} synthetic ingredients ({} per block x {} blocks) in {} ms",
			perBlock * BLOCKS, perBlock, BLOCKS, (System.nanoTime() - start) / 1_000_000L);
	}
}