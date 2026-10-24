package dev.jeasync.mixin.collect;

import dev.jeasync.AsyncConfig;
import dev.jeasync.MixinApplied;
import mezz.jei.api.gui.builder.IIngredientAcceptor;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IIngredientManager;
import mezz.jei.api.runtime.IIngredientVisibility;
import mezz.jei.library.ingredients.RecipeIngredientSupplier;
import mezz.jei.library.ingredients.RecipeIngredientSupplier.FocusLink;
import mezz.jei.library.recipes.PluginManager;
import mezz.jei.library.recipes.collect.RecipeMap;
import mezz.jei.library.recipes.collect.RecipeTypeData;
import mezz.jei.library.recipes.collect.RecipeTypeDataMap;
import mezz.jei.library.util.IngredientSupplierHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Makes {@link mezz.jei.library.recipes.RecipeManagerInternal} safe for concurrent recipe
 * registration, and drops the recipes whose serializer is on {@code skippedRecipeSerializers}
 * from registration entirely.
 * <p>
 * Two members need cross-thread guarantees:
 * <ul>
 *     <li>{@code hiddenRecipeTypes}, a plain {@code HashSet} in a final field. A mixin cannot
 *     replace a final field's contents, so its accesses are taken under a lock instead.</li>
 *     <li>{@code recipeCategoriesVisibleCache}. Upstream marks this {@code volatile}, but a mixin
 *     cannot add a modifier to an existing field, so its accesses are locked too. Locking gives
 *     the same happens-before guarantee that {@code volatile} would.</li>
 * </ul>
 * Every method that touches either one locks on {@code this}. Contention is negligible: these are
 * short reads and small-set updates.
 * <p>
 * The skip filter runs in {@code addRecipes} before any recipe is entered in a table: no rows, no
 * focus links, no catalysts. Only recipes that are vanilla {@code Recipe} objects can be matched,
 * by the registry name of their serializer; Tinkers' Construct's generated
 * {@code tipped_tool_transform} recipes, one per tool layout and modifier set, are the entry of
 * note. Synthetic JEI recipes and the dynamic {@code IRecipeManagerPlugin} path are unaffected.
 * A skipped recipe never returns a supplier, so the caller's bookkeeping sees nothing registered.
 * The serializer is read once per recipe and the read is guarded: JEI's own {@code JeiShapedRecipe}
 * resolves a deferred registry entry, and some mod recipes compute their serializer from their own
 * state, so both can fail while registration is still in flight. A recipe that cannot answer gets
 * registered unfiltered rather than taking its plugin down.
 * <p>
 * A serializer or recipe type listed in {@code dedupRecipeSerializers} instead keeps its recipes but
 * collapses them to one per result: Tinkers' tipped-tool transforms register the same tipped effect
 * once per tool and material, Apotheosis and Apothic affix recipes register the same affix once per
 * item, and Tinkers' casting and melting families register the same part or metal once per cast,
 * ore and damageable item, so only the first recipe of each distinct result is kept and the
 * redundant copies are dropped. An effect is whichever {@code Modifier}-, {@code Enchant}- or
 * {@code Affix}-typed field the recipe class declares, and a recipe that declares none is keyed on
 * its {@code getOutput} stack or fluid, ignoring the amount, so one mechanism covers all of them.
 * The category still shows its real recipes, like the anvil keeping its repair list while dropping
 * only the redundant enchantment copies. Naming a recipe type covers every serializer registered
 * under it, which is how one entry catches all of Tinkers' casting variants; only vanilla
 * {@code Recipe} objects can be keyed, so a plugin's synthetic recipes under a listed type register
 * untouched.
 * <p>
 * Targets upstream 15.74.0.227 and 15.74.0.228 (class unchanged between them).
 */
@Mixin(value = mezz.jei.library.recipes.RecipeManagerInternal.class, remap = false)
public abstract class RecipeManagerInternalMixin {
	private static final Logger LOGGER = LogManager.getLogger();

	@Unique
	private static final Set<String> jeasync$skippedReported = ConcurrentHashMap.newKeySet();

	@Unique
	private static final Set<String> jeasync$dedupReported = ConcurrentHashMap.newKeySet();

	@Unique
	private static final ThreadLocal<Set<String>> jeasync$dedupKeys = ThreadLocal.withInitial(HashSet::new);

	@Shadow
	private Set<RecipeType<?>> hiddenRecipeTypes;

	@Shadow
	private List<IRecipeCategory<?>> recipeCategoriesVisibleCache;

	@Shadow
	private RecipeTypeDataMap recipeTypeDataMap;

	@Shadow
	private EnumMap<RecipeIngredientRole, RecipeMap> recipeMaps;

	@Shadow
	private PluginManager pluginManager;

	@Shadow
	private IIngredientVisibility ingredientVisibility;

	@Shadow
	private IIngredientManager ingredientManager;

	@Shadow
	private Comparator<IRecipeCategory<?>> recipeCategoryComparator;

	@Shadow
	private List<IRecipeCategory<?>> recipeCategories;

	@Shadow
	private <T> Stream<T> getRecipesStream(RecipeType<T> recipeType, IFocusGroup focuses, boolean includeHidden) {
		throw new AssertionError();
	}

	@Shadow
	public <T> Stream<Consumer<IIngredientAcceptor<?>>> getRecipeCatalystGroups(RecipeType<T> recipeType, boolean includeHidden) {
		throw new AssertionError();
	}

	@Shadow
	private <T> RecipeIngredientSupplier addRecipe(IRecipeCategory<T> recipeCategory, T recipe, Set<T> hiddenRecipes) {
		throw new AssertionError();
	}

	// Deliberately not named getRecipeCategoriesUncached: a mixin method with the same name as the
	// target's own method replaces it, so the @Invoker below would resolve back to this shim and
	// recurse until the stack blew.
	private Stream<IRecipeCategory<?>> jeiAsyncUncachedCategories(Collection<IRecipeCategory<?>> recipeCategories, IFocusGroup focuses, boolean includeHidden) {
		return ((RecipeManagerInternalInvoker) (Object) this).jeiAsyncRecipeCategoriesUncached(recipeCategories, focuses, includeHidden);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock category-cache invalidation so workers can add recipes together, drop the recipes whose serializer is on {@code skippedRecipeSerializers}, and collapse the recipes whose serializer or recipe type is on {@code dedupRecipeSerializers} to one per effect or product, before anything is built for them.
	 */
	@Overwrite
	public <T> void addRecipes(RecipeType<T> recipeType, List<T> recipes) {
		LOGGER.debug("Adding recipes: {}", recipeType);
		RecipeTypeData<T> recipeTypeData = recipeTypeDataMap.get(recipeType);
		IRecipeCategory<T> recipeCategory = recipeTypeData.getRecipeCategory();
		Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();

		List<T> addedRecipes = new ArrayList<>(recipes.size());
		boolean deferLinks = AsyncConfig.deferFocusLinks();
		int dropped = 0;
		int deduped = 0;
		Set<String> dedupKeys = jeasync$dedupKeys.get();
		dedupKeys.clear();
		try {
			for (T recipe : recipes) {
				if (jeasync$isSkippedRecipe(recipe)) {
					dropped++;
					continue;
				}
				if (jeasync$isRedundantRecipe(recipeType, recipe)) {
					deduped++;
					continue;
				}
				RecipeIngredientSupplier ingredientSupplier = addRecipe(recipeCategory, recipe, hiddenRecipes);
				if (ingredientSupplier != null) {
					if (deferLinks) {
						jeasync$markDeferred();
					} else {
						recipeTypeData.addFocusLinks(recipe, ingredientSupplier.getFocusLinks());
					}
					addedRecipes.add(recipe);
				}
			}
		} finally {
			dedupKeys.clear();
		}
		if (dropped > 0) {
			jeasync$markSkipped(recipeType, dropped);
		}
		if (deduped > 0) {
			jeasync$markDeduped(recipeType, deduped);
		}

		if (!addedRecipes.isEmpty()) {
			recipeTypeData.addRecipes(addedRecipes);
			synchronized (this) {
				recipeCategoriesVisibleCache = null;
			}
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the hidden-type set so a visibility check does not race a hide.
	 */
	@Overwrite
	public boolean isCategoryHidden(IRecipeCategory<?> recipeCategory, IFocusGroup focuses) {
		// hide the category if it has been explicitly hidden
		RecipeType<?> recipeType = recipeCategory.getRecipeType();
		synchronized (this) {
			if (hiddenRecipeTypes.contains(recipeType)) {
				return true;
			}
		}

// hide the category if it has catalysts, but they have all been hidden
		if (getRecipeCatalystGroups(recipeType, true).findAny().isPresent() &&
				getRecipeCatalystGroups(recipeType, false).findAny().isEmpty())
		{
			return true;
		}

		// hide the category if it has no recipes, or if the recipes have all been hidden
		Stream<?> visibleRecipes = getRecipesStream(recipeType, focuses, false);
		return visibleRecipes.findAny().isEmpty();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the visible-category cache so readers do not see a half-cleared list.
	 */
	@Overwrite
	private Stream<IRecipeCategory<?>> getRecipeCategoriesCached(Collection<IRecipeCategory<?>> recipeCategories, IFocusGroup focuses, boolean includeHidden) {
		if (recipeCategories.isEmpty() && focuses.isEmpty() && !includeHidden) {
			List<IRecipeCategory<?>> cached;
			synchronized (this) {
				cached = recipeCategoriesVisibleCache;
			}
			if (cached == null) {
				cached = jeiAsyncUncachedCategories(recipeCategories, focuses, includeHidden).toList();
				synchronized (this) {
					recipeCategoriesVisibleCache = cached;
				}
			}
			return cached.stream();
		}

		return jeiAsyncUncachedCategories(recipeCategories, focuses, includeHidden);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock cache invalidation so a hide does not race a reader of the visible categories.
	 */
	@Overwrite
	public <T> void hideRecipes(RecipeType<T> recipeType, Collection<T> recipes) {
		RecipeTypeData<T> recipeTypeData = recipeTypeDataMap.get(recipeType);
		Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();
		hiddenRecipes.addAll(recipes);
		synchronized (this) {
			recipeCategoriesVisibleCache = null;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock cache invalidation so an unhide does not race a reader of the visible categories.
	 */
	@Overwrite
	public <T> void unhideRecipes(RecipeType<T> recipeType, Collection<T> recipes) {
		RecipeTypeData<T> recipeTypeData = recipeTypeDataMap.get(recipeType);
		Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();
		hiddenRecipes.removeAll(recipes);
		synchronized (this) {
			recipeCategoriesVisibleCache = null;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Deferred focus links build at a recipe's first visibility query and are then cached in
	 * the type data, so later queries are as fast as upstream's stored links. Recipes that are never
	 * queried hold no links. Without deferral this matches upstream: links stored at add time, or the
	 * rebuild fallback for recipes that never went through registration.
	 */
	@Overwrite
	private <T> List<FocusLink> getFocusLinks(RecipeTypeData<T> recipeTypeData, T recipe) {
		List<FocusLink> focusLinks = recipeTypeData.getFocusLinks(recipe);
		if (focusLinks != null) {
			return focusLinks;
		}
		RecipeIngredientSupplier ingredientSupplier = IngredientSupplierHelper.getIngredientSupplier(
				recipe,
				recipeTypeData.getRecipeCategory(),
				ingredientManager,
				recipeMaps.keySet()
		);
		List<FocusLink> links = ingredientSupplier.getFocusLinks();
		if (AsyncConfig.deferFocusLinks()) {
			recipeTypeData.addFocusLinks(recipe, links);
		}
		return links;
	}

	@Unique
	private static void jeasync$markDeferred() {
		if (MixinApplied.isApplied("collect.DeferredFocusLinks")) {
			return;
		}
		MixinApplied.mark("collect.DeferredFocusLinks");
		LOGGER.info(
			"jeasync: focus links build on the first visibility query instead of at recipe registration"
		);
	}

	/**
	 * Whether the recipe's serializer is configured off. Only recipes that are vanilla {@code Recipe}
	 * objects carry a serializer; synthetic JEI recipes, including add-ons' own wrappers, pass
	 * through untouched.
	 */
	@Unique
	private static boolean jeasync$isSkippedRecipe(Object recipe) {
		List<String> skipped = AsyncConfig.skippedRecipeSerializers();
		if (skipped.isEmpty()) {
			return false;
		}
		ResourceLocation serializerId = jeasync$serializerId(recipe);
		return serializerId != null && skipped.contains(serializerId.toString());
	}

	@Unique
	private static void jeasync$markSkipped(RecipeType<?> recipeType, int skipped) {
		String key = recipeType.getUid().toString();
		if (!jeasync$skippedReported.add(key)) {
			return;
		}
		MixinApplied.mark("collect.SkippedRecipeSerializers");
		LOGGER.info(
			"jeasync: {} recipe(s) from {} are not registered, their serializer is in skippedRecipeSerializers",
			skipped,
			key
		);
	}

	/**
	 * Whether this recipe is a redundant copy of a serializer or recipe type configured for dedup, so
	 * only the first of each effect is kept. The effect is read reflectively from the first field the
	 * recipe class carries: Tinkers' tipped-tool transforms store theirs as {@code modifier},
	 * Apotheosis and Apothic affix recipes as {@code affix} or {@code affixType}. A recipe with no
	 * such field falls back to what it produces, its {@code getOutput} stack or fluid, so Tinkers'
	 * casting and melting recipes collapse on the part or metal they make. A recipe whose effect and
	 * product can neither be read stays distinct.
	 */
	@Unique
	private static boolean jeasync$isRedundantRecipe(RecipeType<?> recipeType, Object recipe) {
		List<String> dedup = AsyncConfig.dedupRecipeSerializers();
		if (dedup.isEmpty()) {
			return false;
		}
		// A recipe type is listed as well as a serializer: Tinkers gives every casting and melting
		// variant its own serializer but keeps them all under one recipe type, so naming the type
		// covers the family instead of enumerating its serializers.
		boolean listed = dedup.contains(recipeType.getUid().toString());
		if (!listed) {
			ResourceLocation serializerId = jeasync$serializerId(recipe);
			listed = serializerId != null && dedup.contains(serializerId.toString());
		}
		// A listed type also covers the synthetic recipes a plugin registers under it, and those have
		// no effect field to read; only a vanilla Recipe can be keyed.
		return listed
			&& recipe instanceof Recipe<?> vanillaRecipe
			&& !jeasync$dedupKeys.get().add(jeasync$effectKey(vanillaRecipe));
	}

	/**
	 * The registry name of a vanilla recipe's serializer, or null when the recipe is a synthetic JEI
	 * recipe with no serializer to read. Some mod and JEI-own recipes resolve their serializer
	 * lazily or through their own state, and that read can fail while registration is still running
	 * off the render thread, so a failure is treated as "no serializer" and the recipe registers
	 * normally rather than taking the whole plugin down with it.
	 */
	@Unique
	private static ResourceLocation jeasync$serializerId(Object recipe) {
		if (!(recipe instanceof Recipe<?> vanillaRecipe)) {
			return null;
		}
		try {
			return ForgeRegistries.RECIPE_SERIALIZERS.getKey(vanillaRecipe.getSerializer());
		} catch (RuntimeException | Error e) {
			LOGGER.debug("jeasync: {} cannot report its serializer yet, it is registered unfiltered", vanillaRecipe.getClass().getName(), e);
			return null;
		}
	}

	/**
	 * The dedup key for a recipe: the first field whose type names an effect the recipe applies.
	 * Tinkers' tipped-tool transform holds a {@code TippedTool.ModifierData}; Apotheosis and Apothic
	 * affix recipes hold an {@code Affix}/{@code AffixInstance}. Matching on the type instead of the
	 * field name keeps one mechanism for both. A recipe with no such field is keyed on what it
	 * produces instead, which is how the casting and melting families are recognised: they have no
	 * effect, only an output. A recipe with neither stays distinct.
	 */
	@Unique
	private static String jeasync$effectKey(Recipe<?> recipe) {
		for (Class<?> type = recipe.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (jeasync$isEffectType(field.getType())) {
					try {
						field.setAccessible(true);
						Object value = field.get(recipe);
						if (value != null) {
							return jeasync$stableKey(value);
						}
					} catch (ReflectiveOperationException ignored) {
						// Inaccessible: try the next candidate field.
					}
				}
			}
		}
		String product = jeasync$productKey(recipe);
		if (product != null) {
			return product;
		}
		return Integer.toHexString(System.identityHashCode(recipe));
	}

	/**
	 * A key for what a recipe produces: a no-argument {@code getOutput} returning an {@code ItemStack}
	 * or a {@code FluidStack}, which is how Tinkers' casting and melting recipes state their result.
	 * The count and the fluid amount are left out on purpose, so the same part or metal collapses into
	 * one entry however much of it a recipe yields. A recipe whose product can only be reported as a
	 * list, like a potion casting recipe, has no such accessor and returns null.
	 */
	@Unique
	private static String jeasync$productKey(Recipe<?> recipe) {
		Object product;
		try {
			product = recipe.getClass().getMethod("getOutput").invoke(recipe);
		} catch (ReflectiveOperationException | RuntimeException | Error e) {
			return null;
		}
		if (product instanceof ItemStack stack && !stack.isEmpty()) {
			ResourceLocation id = ForgeRegistries.ITEMS.getKey(stack.getItem());
			if (id == null) {
				return null;
			}
			CompoundTag tag = stack.getTag();
			return "item:" + id + (tag == null ? "" : tag);
		}
		if (product instanceof FluidStack fluid && !fluid.isEmpty()) {
			ResourceLocation id = ForgeRegistries.FLUIDS.getKey(fluid.getFluid());
			return id == null ? null : "fluid:" + id;
		}
		return null;
	}

	/**
	 * A stable id for an effect value. An {@code AffixInstance} record stringifies with the rolled
	 * stack and level, and a plain {@code Affix} with an identity hash, so neither collapses two
	 * copies of the same affix on its own. An accessor naming the affix or modifier is preferred,
	 * then the same accessor on a record component, then {@code toString}.
	 */
	@Unique
	private static String jeasync$stableKey(Object value) {
		String id = jeasync$nameOf(value);
		if (id != null) {
			return id;
		}
		if (value instanceof Record record) {
			for (Field component : record.getClass().getDeclaredFields()) {
				try {
					component.setAccessible(true);
					id = jeasync$nameOf(component.get(record));
					if (id != null) {
						return id;
					}
				} catch (ReflectiveOperationException ignored) {
					// Inaccessible component: try the next.
				}
			}
		}
		return value.toString();
	}

	/**
	 * The effect's registry-style name, if the value or one of its zero-argument accessors exposes
	 * one: {@code getName} or {@code getRegistryName} returning a {@code Component}, {@code Holder},
	 * {@code ResourceLocation} or {@code String}. A name with a colon keeps its namespace.
	 */
	@Unique
	private static String jeasync$nameOf(Object value) {
		if (value == null) {
			return null;
		}
		if (value instanceof net.minecraft.core.Holder<?> holder && holder.isBound()) {
			String keyed = holder.unwrapKey().map(key -> key.location().toString()).orElse(null);
			if (keyed != null) {
				return keyed;
			}
		}
		for (String accessor : new String[] {"getName", "getRegistryName"}) {
			try {
				Object named = value.getClass().getMethod(accessor).invoke(value);
				if (named instanceof ResourceLocation location) {
					return location.toString();
				}
				if (named instanceof net.minecraft.core.Holder<?> holder && holder.isBound()) {
					String keyedName = holder.unwrapKey().map(key -> key.location().toString()).orElse(null);
					if (keyedName != null) {
						return keyedName;
					}
				}
				if (named != null && !(named instanceof net.minecraft.world.item.ItemStack)) {
					String text = named.toString();
					int style = text.indexOf('{');
					if (style > 0) {
						text = text.substring(0, style);
					}
					// Recipe.getName() is the group, usually empty; a blank key would collapse
					// every recipe of the serializer into one.
					if (text.isBlank() || text.equals("{}")) {
						continue;
					}
					return text;
				}
			} catch (ReflectiveOperationException ignored) {
				// Accessor absent or not public: try the next.
			}
		}
		return null;
	}

	@Unique
	private static boolean jeasync$isEffectType(Class<?> field) {
		String name = field.getSimpleName();
		return name.contains("Affix") || name.contains("Modifier") || name.contains("Enchant") || name.contains("MobEffect");
	}

	@Unique
	private static void jeasync$markDeduped(RecipeType<?> recipeType, int dropped) {
		String key = recipeType.getUid().toString();
		if (!jeasync$dedupReported.add(key)) {
			return;
		}
		MixinApplied.mark("collect.DedupRecipeSerializers");
		LOGGER.info(
			"jeasync: {} redundant recipe(s) from {} collapsed to one per effect or product",
			dropped,
			key
		);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the hidden-type set so a visibility check does not race a hide.
	 */
	@Overwrite
	public void hideRecipeCategory(RecipeType<?> recipeType) {
		synchronized (this) {
			hiddenRecipeTypes.add(recipeType);
			recipeCategoriesVisibleCache = null;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the hidden-type set so a category unhide does not race a visibility check.
	 */
	@Overwrite
	public void unhideRecipeCategory(RecipeType<?> recipeType) {
		recipeTypeDataMap.validate(recipeType);
		synchronized (this) {
			hiddenRecipeTypes.remove(recipeType);
			recipeCategoriesVisibleCache = null;
		}
	}
}