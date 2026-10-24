package dev.jeasync.mixin.collect;

import dev.jeasync.AnvilEnchantCache;
import dev.jeasync.MixinApplied;
import dev.jeasync.PlainStacks;
import mezz.jei.api.recipe.vanilla.IJeiGrindstoneRecipe;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.platform.IPlatformRecipeHelper;
import mezz.jei.common.util.RegistryUtil;
import mezz.jei.library.plugins.vanilla.grindstone.GrindstoneRecipe;
import mezz.jei.library.plugins.vanilla.grindstone.GrindstoneRecipeMaker;
import mezz.jei.library.util.ResourceLocationUtil;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.GrindstoneMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.Enchantment;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Gives the grindstone the same shape the anvil has: keep the real recipes, drop only the
 * redundant copies.
 * <p>
 * The repair scan is one cheap pair per repairable item, the same kind of list the anvil keeps, so
 * every pair is built once without the grindstone menu. The disenchant scan is JEI's item ×
 * enchantment × level cross product; the item copies of one enchantment are redundant (JEI's uid
 * already collapses on enchantment and level), so the scan keeps one level-1 recipe per real
 * enchantment instead of the full product. Neither scan is gated by config, so the category always
 * holds its recipes and never hides itself.
 * <p>
 * Targets upstream 15.74.0.227 and 15.74.0.228 (class unchanged between them).
 */
@Mixin(value = GrindstoneRecipeMaker.class, remap = false)
public class GrindstoneRecipeMakerMixin {
	@Unique
	private static volatile boolean jeasync$marked;

	@Shadow
	private static boolean canEnchant(
		IPlatformRecipeHelper platformHelper,
		ItemStack stack,
		Enchantment enchantment,
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
	 * @reason Grindstone registration asks for enchant categories on every tool. Keep those answers until this scan returns. The stacks the scan builds skip the constructor damage compound.
	 */
	@Redirect(
		method = "getGrindstoneRecipes(Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/common/platform/IPlatformRecipeHelper;)Ljava/util/List;",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/library/plugins/vanilla/grindstone/GrindstoneRecipeMaker;getGrindstoneRecipes(Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/common/platform/IPlatformRecipeHelper;Lnet/minecraft/world/inventory/GrindstoneMenu;)Ljava/util/List;"
		)
	)
	private static List<IJeiGrindstoneRecipe> jeasync$scanWithCategoryCache(
		IIngredientManager ingredientManager,
		IPlatformRecipeHelper platformHelper,
		GrindstoneMenu grindstoneMenu
	) {
		jeasync$mark();
		AnvilEnchantCache.enter();
		PlainStacks.enter();
		try {
			return GrindstoneRecipeMaker.getGrindstoneRecipes(ingredientManager, platformHelper, grindstoneMenu);
		} finally {
			AnvilEnchantCache.exit();
			PlainStacks.exit();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason The disenchant scan paired every item with every enchantment and level, but the item
	 * copies of one enchantment are what JEI's uid already collapses. Keep one recipe per real
	 * enchantment (level 1, first item that takes it) and drop only those redundant item copies.
	 */
	@Overwrite
	private static Stream<IJeiGrindstoneRecipe> getDisenchantRecipes(
		IPlatformRecipeHelper platformHelper,
		GrindstoneMenu grindstoneMenu
	) {
		if (platformHelper == null || grindstoneMenu == null) {
			return Stream.empty();
		}
		Registry<Enchantment> enchantmentRegistry = RegistryUtil.getRegistry(Registries.ENCHANTMENT);
		Registry<Item> itemRegistry = RegistryUtil.getRegistry(Registries.ITEM);
		List<IJeiGrindstoneRecipe> recipes = new ArrayList<>();
		for (Enchantment enchantment : enchantmentRegistry) {
			if (enchantment.isCurse() || enchantment.getMaxLevel() < 1) {
				continue;
			}
			ResourceLocation enchantmentId = enchantmentRegistry.getKey(enchantment);
			for (Item item : itemRegistry) {
				ItemStack plain = new ItemStack(item);
				if (!plain.isEnchantable()) {
					continue;
				}
				if (!canEnchant(platformHelper, plain, enchantment, enchantmentId)) {
					continue;
				}
				ItemStack enchanted = plain.copy();
				enchanted.enchant(enchantment, 1);
				ResourceLocation uid = getDisenchantmentRecipeUid(enchantmentId, 1);
				recipes.add(new GrindstoneRecipe(
					List.of(enchanted),
					List.of(ItemStack.EMPTY),
					List.of(plain),
					-1,
					-1,
					uid
				));
				break;
			}
		}
		return recipes.stream();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Repair registration sent every damageable stack through the grindstone menu. The
	 * list is one cheap pair per repairable item, the same kind the anvil keeps, so build it once
	 * without the menu and keep every pair instead of collapsing to a single example.
	 */
	@Overwrite
	private static Stream<IJeiGrindstoneRecipe> getRepairRecipes(
		IPlatformRecipeHelper platformHelper,
		IIngredientManager ingredientManager,
		GrindstoneMenu grindstoneMenu
	) {
		if (platformHelper == null || ingredientManager == null || grindstoneMenu == null) {
			return Stream.empty();
		}
		Registry<Item> items = RegistryUtil.getRegistry(Registries.ITEM);
		List<IJeiGrindstoneRecipe> recipes = new ArrayList<>();
		for (Item item : items) {
			ItemStack plain = new ItemStack(item);
			if (!plain.isDamageableItem() || !plain.isRepairable()) {
				continue;
			}
			int max = plain.getMaxDamage();
			int damage = max * 3 / 4;
			if (damage <= 0) {
				continue;
			}
			int remain = max - damage;
			int repaired = max - (remain + remain + max * 5 / 100);
			if (repaired < 0) {
				repaired = 0;
			}
			if (repaired >= damage) {
				continue;
			}
			ItemStack top = plain.copy();
			top.setDamageValue(damage);
			ItemStack bottom = top.copy();
			ItemStack output = plain.copy();
			if (repaired > 0) {
				output.setDamageValue(repaired);
			}
			String uidPath = ResourceLocationUtil.sanitizePath("/grindstone.self_repair." + item.getDescriptionId());
			recipes.add(new GrindstoneRecipe(
				List.of(top),
				List.of(bottom),
				List.of(output),
				-1,
				-1,
				new ResourceLocation("minecraft", uidPath)
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
			"jeasync: grindstone reuses enchant categories during its scan"
		);
	}
}
