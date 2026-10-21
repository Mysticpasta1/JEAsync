package dev.jeiasync.mixin.collect;

import dev.jeiasync.AnvilEnchantCache;
import dev.jeiasync.AnvilResult;
import dev.jeiasync.MixinApplied;
import mezz.jei.api.recipe.vanilla.IJeiAnvilRecipe;
import mezz.jei.library.plugins.vanilla.anvil.AnvilRecipeMaker;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.List;

/**
 * Builds anvil book and repair outputs without the anvil menu.
 * <p>
 * The category cache still covers enchantment checks that run before a book is accepted. The menu
 * is the call that handed every accepted pair to every anvil listener.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(value = AnvilRecipeMaker.class, remap = false)
public class AnvilRecipeMakerMixin {
	@Unique
	private static volatile boolean jeiasync$marked;

	/**
	 * @author Mysticpasta1
	 * @reason Each book level and each repair opened the anvil menu, so every listener handled that result. Build the output from the two stacks instead.
	 */
	@Overwrite
	private ItemStack getAnvilOutput(ItemStack leftStack, ItemStack rightStack) {
		jeiasync$mark();
		return AnvilResult.combine(leftStack, rightStack);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Registering recipes waited on anvil book levels, and each level rebuilt enchant categories for the same tool. Reuse them until this scan returns.
	 */
	@Redirect(
		method = "getAnvilRecipes(Lmezz/jei/api/recipe/vanilla/IVanillaRecipeFactory;Lmezz/jei/api/runtime/IIngredientManager;Lnet/minecraft/world/inventory/AnvilMenu;)Ljava/util/List;",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/library/plugins/vanilla/anvil/AnvilRecipeMaker;getAnvilRecipes()Ljava/util/List;"
		)
	)
	private static List<IJeiAnvilRecipe> jeiasync$scanWithCategoryCache(AnvilRecipeMaker maker) {
		jeiasync$mark();
		AnvilEnchantCache.enter();
		try {
			return maker.getAnvilRecipes();
		} finally {
			AnvilEnchantCache.exit();
		}
	}

	@Unique
	private static void jeiasync$mark() {
		if (jeiasync$marked) {
			return;
		}
		jeiasync$marked = true;
		MixinApplied.mark("collect.AnvilRecipeMakerMixin");
		org.apache.logging.log4j.LogManager.getLogger(AnvilRecipeMakerMixin.class).info(
			"JEI Async: anvil book and repair results are built without the anvil menu"
		);
	}
}
