package dev.jeasync.mixin.collect;

import dev.jeasync.MixinApplied;
import mezz.jei.api.recipe.vanilla.IJeiGrindstoneRecipe;
import mezz.jei.common.platform.IPlatformIngredientHelper;
import mezz.jei.common.platform.IPlatformRecipeHelper;
import mezz.jei.common.util.RegistryUtil;
import mezz.jei.library.plugins.vanilla.grindstone.GrindstoneRecipe;
import mezz.jei.library.plugins.vanilla.grindstone.GrindstoneRecipeMaker;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Collapses JEI's grindstone disenchant scan to one recipe per enchantment.
 * <p>
 * JEI 19 builds an enchanted copy of every item that supports each enchantment, for every level
 * up to the enchantment's maximum. The item and level copies add nothing to the viewer: the uid
 * already identifies the recipe by enchantment and level, and a disenchant result is decided by
 * the enchantment alone. On a large pack that cross product is the single largest retained
 * recipe list. This keeps the first real item per enchantment at level one and drops only the
 * redundant copies. Repair pairs are untouched and still come from JEI.
 */
@Mixin(value = GrindstoneRecipeMaker.class, remap = false)
public class GrindstoneRecipeMakerMixin {
	@Unique
	private static volatile boolean jeasync$marked;

	@Shadow
	private static boolean canEnchant(
		IPlatformRecipeHelper platformHelper,
		ItemStack stack,
		Holder<Enchantment> enchantment,
		ResourceLocation enchantmentId
	) {
		throw new AssertionError();
	}

	@Shadow
	private static ResourceLocation getDisenchantmentRecipeUid(ResourceLocation enchantmentId, int level) {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason The item and level copies of one enchantment are redundant. Keep the first item
	 * that accepts each enchantment, at level one, and let the repair scan stay as JEI wrote it.
	 */
	@Overwrite
	private static Stream<IJeiGrindstoneRecipe> getDisenchantRecipes(
		IPlatformRecipeHelper platformHelper,
		IPlatformIngredientHelper ingredientHelper,
		GrindstoneMenu grindstoneMenu
	) {
		jeasync$mark();
		if (platformHelper == null || ingredientHelper == null || grindstoneMenu == null) {
			return Stream.empty();
		}
		Registry<Enchantment> registry = RegistryUtil.getRegistry(Registries.ENCHANTMENT);
		List<IJeiGrindstoneRecipe> recipes = new ArrayList<>();
		for (Holder.Reference<Enchantment> enchantmentHolder : registry.holders().toList()) {
			if (enchantmentHolder.is(EnchantmentTags.CURSE)) {
				continue;
			}
			Enchantment enchantment = enchantmentHolder.value();
			if (enchantment.getMaxLevel() < 1) {
				continue;
			}
			ResourceLocation enchantmentId = registry.getResourceKey(enchantment)
				.map(ResourceKey::location)
				.orElse(null);
			if (enchantmentId == null) {
				continue;
			}
			ItemStack chosen = null;
			for (Holder<Item> itemHolder : ingredientHelper.getSupportedItems(enchantmentHolder)) {
				ItemStack plain = new ItemStack(itemHolder);
				if (!plain.isEnchantable() || !canEnchant(platformHelper, plain, enchantmentHolder, enchantmentId)) {
					continue;
				}
				chosen = plain;
				break;
			}
			if (chosen == null) {
				continue;
			}
			ItemStack enchanted = chosen.copy();
			enchanted.enchant(enchantmentHolder, 1);
			ItemStack output = platformHelper.getGrindstoneResult(grindstoneMenu, enchanted, ItemStack.EMPTY);
			if (output.isEmpty()) {
				continue;
			}
			recipes.add(new GrindstoneRecipe(
				List.of(enchanted),
				List.of(ItemStack.EMPTY),
				List.of(output),
				-1,
				-1,
				getDisenchantmentRecipeUid(enchantmentId, 1)
			));
		}
		return recipes.stream();
	}

	@Unique
	private static void jeasync$mark() {
		if (jeasync$marked) {
			return;
		}
		jeasync$marked = true;
		MixinApplied.mark("collect.GrindstoneRecipeMakerMixin");
		LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
			"jeasync: grindstone keeps one disenchant recipe per enchantment"
		);
	}
}
