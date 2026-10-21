package dev.jeiasync.mixin.collect;

import dev.jeiasync.AnvilEnchantCache;
import dev.jeiasync.MixinApplied;
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
import net.minecraft.world.item.enchantment.EnchantmentCategory;
import org.apache.logging.log4j.LogManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds the same grindstone disenchant recipes without a stack per item per enchantment.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(value = GrindstoneRecipeMaker.class, remap = false)
public class GrindstoneRecipeMakerMixin {
	@Unique
	private static volatile boolean jeiasync$marked;

	@Unique
	private static final ClassValue<Boolean> jeiasync$enchantUsesCategory = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return jeiasync$declarer(type, "canEnchant", ItemStack.class) == Enchantment.class
				&& jeiasync$declarer(type, "canApplyAtEnchantingTable", ItemStack.class) == Enchantment.class;
		}
	};

	@Unique
	private static final ClassValue<Boolean> jeiasync$itemUsesCategory = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return jeiasync$declarer(type, "canApplyAtEnchantingTable", ItemStack.class, Enchantment.class) == null;
		}
	};

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
	 * @reason Grindstone registration asks for enchant categories on every tool. Keep those answers until this scan returns.
	 */
	@Redirect(
		method = "getGrindstoneRecipes(Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/common/platform/IPlatformRecipeHelper;)Ljava/util/List;",
		at = @At(
			value = "INVOKE",
			target = "Lmezz/jei/library/plugins/vanilla/grindstone/GrindstoneRecipeMaker;getGrindstoneRecipes(Lmezz/jei/api/runtime/IIngredientManager;Lmezz/jei/common/platform/IPlatformRecipeHelper;Lnet/minecraft/world/inventory/GrindstoneMenu;)Ljava/util/List;"
		)
	)
	private static List<IJeiGrindstoneRecipe> jeiasync$scanWithCategoryCache(
		IIngredientManager ingredientManager,
		IPlatformRecipeHelper platformHelper,
		GrindstoneMenu grindstoneMenu
	) {
		jeiasync$mark();
		AnvilEnchantCache.enter();
		try {
			return GrindstoneRecipeMaker.getGrindstoneRecipes(ingredientManager, platformHelper, grindstoneMenu);
		} finally {
			AnvilEnchantCache.exit();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason The disenchant scan asked the grindstone menu for every item and every level. Enchantments that still use the category test share that answer, and every level of one enchantment shows the same plain stack.
	 */
	@Overwrite
	private static Stream<IJeiGrindstoneRecipe> getDisenchantRecipes(
		IPlatformRecipeHelper platformHelper,
		GrindstoneMenu grindstoneMenu
	) {
		Registry<Enchantment> enchantmentRegistry = RegistryUtil.getRegistry(Registries.ENCHANTMENT);
		Registry<Item> itemRegistry = RegistryUtil.getRegistry(Registries.ITEM);
		List<Enchantment> enchantments = enchantmentRegistry.stream().toList();
		List<ItemStack> enchantable = new ArrayList<>();
		for (Item item : itemRegistry) {
			ItemStack stack = new ItemStack(item);
			if (stack.isEnchantable()) {
				enchantable.add(stack);
			}
		}
		boolean[] usesCategory = new boolean[enchantable.size()];
		for (int i = 0; i < enchantable.size(); i++) {
			usesCategory[i] = jeiasync$itemUsesCategory.get(enchantable.get(i).getItem().getClass());
		}
		Map<EnchantmentCategory, boolean[]> categoryMasks = new IdentityHashMap<>();

		List<IJeiGrindstoneRecipe> grindstoneRecipes = new ArrayList<>();
		for (Enchantment enchantment : enchantments) {
			if (enchantment.isCurse()) {
				continue;
			}
			ResourceLocation enchantmentId = enchantmentRegistry.getKey(enchantment);
			boolean enchantUsesCategory = jeiasync$enchantUsesCategory.get(enchantment.getClass());
			boolean[] mask = enchantUsesCategory && enchantment.category != null
				? jeiasync$categoryMask(enchantment.category, enchantable, usesCategory, categoryMasks)
				: null;
			List<ItemStack> supportedItems = new ArrayList<>();
			for (int i = 0; i < enchantable.size(); i++) {
				ItemStack stack = enchantable.get(i);
				boolean supported;
				if (mask != null && usesCategory[i]) {
					supported = mask[i];
				} else {
					supported = canEnchant(platformHelper, stack, enchantment, enchantmentId);
				}
				if (supported) {
					supportedItems.add(stack);
				}
			}
			int levels = Math.min(enchantment.getMaxLevel(), 10);
			if (levels < 1 || supportedItems.isEmpty()) {
				continue;
			}

			List<ItemStack> bottomInputs = new ArrayList<>(supportedItems.size());
			List<ItemStack> outputs = new ArrayList<>(supportedItems.size());
			for (ItemStack stack : supportedItems) {
				bottomInputs.add(ItemStack.EMPTY);
				outputs.add(stack.copy());
			}
			for (int level = 1; level <= levels; level++) {
				List<ItemStack> topInputs = new ArrayList<>(supportedItems.size());
				for (ItemStack stack : supportedItems) {
					ItemStack enchantedStack = stack.copy();
					enchantedStack.enchant(enchantment, level);
					topInputs.add(enchantedStack);
				}
				ResourceLocation uid = getDisenchantmentRecipeUid(enchantmentId, level);
				grindstoneRecipes.add(new GrindstoneRecipe(topInputs, bottomInputs, outputs, -1, -1, uid));
			}
		}
		return grindstoneRecipes.stream();
	}

	@Unique
	private static boolean[] jeiasync$categoryMask(
		EnchantmentCategory category,
		List<ItemStack> enchantable,
		boolean[] usesCategory,
		Map<EnchantmentCategory, boolean[]> masks
	) {
		boolean[] cached = masks.get(category);
		if (cached != null) {
			return cached;
		}
		boolean[] mask = new boolean[enchantable.size()];
		for (int i = 0; i < enchantable.size(); i++) {
			if (!usesCategory[i]) {
				continue;
			}
			try {
				mask[i] = category.canEnchant(enchantable.get(i).getItem());
			} catch (RuntimeException exception) {
				mask[i] = false;
				LogManager.getLogger(GrindstoneRecipeMakerMixin.class).error(
					"Failed to check enchantment category {} for {}",
					category,
					enchantable.get(i).getItem(),
					exception
				);
			}
		}
		masks.put(category, mask);
		return mask;
	}

	@Unique
	private static Class<?> jeiasync$declarer(Class<?> type, String name, Class<?>... parameters) {
		for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
			try {
				return current.getDeclaredMethod(name, parameters).getDeclaringClass();
			} catch (NoSuchMethodException ignored) {
				// Declared on a superclass, or only as an interface default.
			}
		}
		return null;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Repair registration sent every damageable stack through the grindstone menu. One plain item of each kind has the same repaired result.
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
	private static void jeiasync$mark() {
		if (jeiasync$marked) {
			return;
		}
		jeiasync$marked = true;
		MixinApplied.mark("collect.GrindstoneRecipeMakerMixin");
		org.apache.logging.log4j.LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
			"JEI Async: grindstone disenchant recipes reuse enchant categories and one plain result per level"
		);
		org.apache.logging.log4j.LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
			"JEI Async: grindstone results are built without the grindstone menu"
		);
	}
}
