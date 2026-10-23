package dev.jeasync.mixin.collect;

import dev.jeasync.AnvilEnchantCache;
import dev.jeasync.AsyncConfig;
import dev.jeasync.MixinApplied;
import dev.jeasync.PlainStacks;
import dev.jeasync.mixin.nbt.ItemStackTagAccess;
import mezz.jei.api.recipe.vanilla.IJeiGrindstoneRecipe;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.common.platform.IPlatformRecipeHelper;
import mezz.jei.common.util.RegistryUtil;
import mezz.jei.library.plugins.vanilla.grindstone.GrindstoneRecipe;
import mezz.jei.library.plugins.vanilla.grindstone.GrindstoneRecipeMaker;
import mezz.jei.library.util.ResourceLocationUtil;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
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
 * Builds the same grindstone disenchant recipes without a stack per item per enchantment, and
 * without a compound per item per recipe.
 * <p>
 * Every enchanted stack for one enchantment at one level carries the identical
 * {@code Enchantments} compound, so the scan builds that compound once and hands every stage of
 * the level's inputs a reference to it. Repair and output stacks carry no compound at all: an
 * undamaged damageable stack reads {@code Damage} as 0 without one. The heap had counted one
 * compound per built stack, tens of millions in a modded pack.
 * <p>
 * One recipe per (enchantment, item, level, up to 10) was the other half of the cost. Since JEI
 * protects the enchantment checks against mod crashes, that full cross product is now always
 * built, where an exception used to cut the scan short. {@code grindstoneDisenchantLevels} caps
 * the level axis: levels above 1 repeat the same inputs and the same plain-item result. The
 * level-1 recipes keep upstream's uids, so the recipes that stay are the same ones JEI had.
 * <p>
 * The repair recipes are skipped by default ({@code skipGrindstoneRepairRecipes}); they are the small
 * vanilla-style two-slot pairs, and with disenchant and repair both off the category is empty and
 * hides itself.
 * <p>
 * Targets upstream 15.74.0.227 and 15.74.0.228 (class unchanged between them).
 */
@Mixin(value = GrindstoneRecipeMaker.class, remap = false)
public class GrindstoneRecipeMakerMixin {
	@Unique
	private static volatile boolean jeasync$marked;

	@Unique
	private static volatile boolean jeasync$markedLevels;

	@Unique
	private static volatile boolean jeasync$markedRepair;

	@Unique
	private static final ClassValue<Boolean> jeasync$enchantUsesCategory = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return jeasync$declarer(type, "canEnchant", ItemStack.class) == Enchantment.class
				&& jeasync$declarer(type, "canApplyAtEnchantingTable", ItemStack.class) == Enchantment.class;
		}
	};

	@Unique
	private static final ClassValue<Boolean> jeasync$itemUsesCategory = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> type) {
			return jeasync$declarer(type, "canApplyAtEnchantingTable", ItemStack.class, Enchantment.class) == null;
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
	 * @reason The disenchant scan asked the grindstone menu for every item and every level. Enchantments that still use the category test share that answer, and every level shows the same plain stack. One enchantment compound per level is shared by every stack of that level, instead of one compound per stack. The level axis is capped by config: with the crash protection in, JEI builds the full cross product where an exception used to stop the scan early.
	 */
	@Overwrite
	private static Stream<IJeiGrindstoneRecipe> getDisenchantRecipes(
		IPlatformRecipeHelper platformHelper,
		GrindstoneMenu grindstoneMenu
	) {
		int levelCap = AsyncConfig.grindstoneDisenchantLevels();
		jeasync$markLevels(levelCap);
		if (levelCap == 0) {
			return Stream.empty();
		}
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
			usesCategory[i] = jeasync$itemUsesCategory.get(enchantable.get(i).getItem().getClass());
		}
		Map<EnchantmentCategory, boolean[]> categoryMasks = new IdentityHashMap<>();

		List<IJeiGrindstoneRecipe> grindstoneRecipes = new ArrayList<>();
		for (Enchantment enchantment : enchantments) {
			if (enchantment.isCurse()) {
				continue;
			}
			ResourceLocation enchantmentId = enchantmentRegistry.getKey(enchantment);
			boolean enchantUsesCategory = jeasync$enchantUsesCategory.get(enchantment.getClass());
			boolean[] mask = enchantUsesCategory && enchantment.category != null
				? jeasync$categoryMask(enchantment.category, enchantable, usesCategory, categoryMasks)
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
			int limit = levelCap < 0 ? 10 : levelCap;
			int levels = Math.min(enchantment.getMaxLevel(), limit);
			if (levels < 1 || supportedItems.isEmpty()) {
				continue;
			}

			List<ItemStack> bottomInputs = new ArrayList<>(supportedItems.size());
			for (int i = 0; i < supportedItems.size(); i++) {
				bottomInputs.add(ItemStack.EMPTY);
			}
			for (int level = 1; level <= levels; level++) {
				ItemStack enchantedStack = supportedItems.get(0).copy();
				enchantedStack.enchant(enchantment, level);
				CompoundTag sharedTag = ((ItemStackTagAccess) (Object) enchantedStack).jeasync$getTag();
				List<ItemStack> topInputs = new ArrayList<>(supportedItems.size());
				topInputs.add(enchantedStack);
				for (int i = 1; i < supportedItems.size(); i++) {
					ItemStack shared = new ItemStack(supportedItems.get(i).getItem());
					((ItemStackTagAccess) (Object) shared).jeasync$setTag(sharedTag);
					topInputs.add(shared);
				}
				ResourceLocation uid = getDisenchantmentRecipeUid(enchantmentId, level);
				grindstoneRecipes.add(new GrindstoneRecipe(topInputs, bottomInputs, supportedItems, -1, -1, uid));
			}
		}
		return grindstoneRecipes.stream();
	}

	@Unique
	private static boolean[] jeasync$categoryMask(
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
	private static Class<?> jeasync$declarer(Class<?> type, String name, Class<?>... parameters) {
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
	 * @reason Repair registration sent every damageable stack through the grindstone menu: one plain item of each kind has the same repaired result, and these recipes are skipped by default under {@code skipGrindstoneRepairRecipes}.
	 */
	@Overwrite
	private static Stream<IJeiGrindstoneRecipe> getRepairRecipes(
		IPlatformRecipeHelper platformHelper,
		IIngredientManager ingredientManager,
		GrindstoneMenu grindstoneMenu
	) {
		if (AsyncConfig.skipGrindstoneRepairRecipes()) {
			jeasync$markRepair();
			return Stream.empty();
		}
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
		org.apache.logging.log4j.LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
			"jeasync: grindstone disenchant recipes reuse enchant categories and one plain result per level"
		);
		org.apache.logging.log4j.LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
			"jeasync: grindstone results are built without the grindstone menu"
		);
		org.apache.logging.log4j.LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
			"jeasync: grindstone enchanted stacks share one compound per enchantment level"
		);
	}

	@Unique
	private static void jeasync$markLevels(int levelCap) {
		if (jeasync$markedLevels || levelCap < 0) {
			return;
		}
		jeasync$markedLevels = true;
		MixinApplied.mark("collect.GrindstoneRecipeMakerMixin");
		if (levelCap == 0) {
			org.apache.logging.log4j.LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
				"jeasync: grindstone disenchant recipes are not built"
			);
			return;
		}
		org.apache.logging.log4j.LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
			"jeasync: grindstone disenchant recipes build {} level(s) per enchantment instead of 10", levelCap
		);
	}

	@Unique
	private static void jeasync$markRepair() {
		if (jeasync$markedRepair) {
			return;
		}
		jeasync$markedRepair = true;
		MixinApplied.mark("collect.GrindstoneRecipeMakerMixin");
		org.apache.logging.log4j.LogManager.getLogger(GrindstoneRecipeMakerMixin.class).info(
			"jeasync: grindstone repair recipes are not built"
		);
	}
}
