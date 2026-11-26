package dev.jeasync.mixin.collect;

import dev.jeasync.AsyncConfig;
import dev.jeasync.MixinApplied;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.library.plugins.vanilla.anvil.AnvilRecipeMaker;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.List;
import java.util.stream.Stream;

/**
 * Lets a pack drop the two anvil scans without touching the rest of JEI.
 * <p>
 * The book-enchantment scan is the ingredient x enchantment x level cross product, and JEI runs
 * it at every recipe load. The repair scan is vanilla's small fixed pair list and is cheap, but a
 * pack that does not want the category can drop it too. With both skipped the anvil category has
 * no recipes and hides itself. Both switches are client config options and read at scan time.
 */
@Mixin(value = AnvilRecipeMaker.class, remap = false)
public class AnvilRecipeMakerMixin {
	@Unique
	private static volatile boolean jeasync$marked;

	@Shadow
	private Stream<IJeiAnvilRecipe> getRepairRecipes() {
		throw new AssertionError();
	}

	@Shadow
	private Stream<IJeiAnvilRecipe> getBookEnchantmentRecipes() {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Skip either anvil scan by config. A skipped scan contributes an empty stream, so
	 * with both off the category registers nothing and hides itself.
	 */
	@Overwrite
	public List<IJeiAnvilRecipe> getAnvilRecipes() {
		jeasync$mark();
		Stream<IJeiAnvilRecipe> repairs;
		if (AsyncConfig.skipAnvilRepairRecipes()) {
			repairs = Stream.empty();
		} else {
			repairs = getRepairRecipes();
		}
		Stream<IJeiAnvilRecipe> enchants;
		if (AsyncConfig.skipAnvilEnchantRecipes()) {
			enchants = Stream.empty();
		} else {
			enchants = getBookEnchantmentRecipes();
		}
		return Stream.concat(repairs, enchants).toList();
	}

	@Unique
	private static void jeasync$mark() {
		if (jeasync$marked) {
			return;
		}
		jeasync$marked = true;
		MixinApplied.mark("collect.AnvilRecipeMakerMixin");
		LogManager.getLogger(AnvilRecipeMakerMixin.class)
			.info("jeasync: anvil scan switches are active");
	}
}
