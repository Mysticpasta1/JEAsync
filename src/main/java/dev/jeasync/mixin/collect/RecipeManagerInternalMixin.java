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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.Stream;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.Recipe;
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
 * <p>
 * Targets upstream 15.74.0.227 and 15.74.0.228 (class unchanged between them).
 */
@Mixin(value = mezz.jei.library.recipes.RecipeManagerInternal.class, remap = false)
public abstract class RecipeManagerInternalMixin {
	private static final Logger LOGGER = LogManager.getLogger();

	@Unique
	private static final Set<String> jeasync$skippedReported = ConcurrentHashMap.newKeySet();

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
	 * @reason Lock category-cache invalidation so workers can add recipes together, and skip the recipes whose serializer is on {@code skippedRecipeSerializers} before anything is built for them.
	 */
	@Overwrite
	public <T> void addRecipes(RecipeType<T> recipeType, List<T> recipes) {
		LOGGER.debug("Adding recipes: {}", recipeType);
		RecipeTypeData<T> recipeTypeData = recipeTypeDataMap.get(recipeType);
		IRecipeCategory<T> recipeCategory = recipeTypeData.getRecipeCategory();
		Set<T> hiddenRecipes = recipeTypeData.getHiddenRecipes();

		List<T> addedRecipes = new ArrayList<>(recipes.size());
		boolean deferLinks = AsyncConfig.deferFocusLinks();
		int skipped = 0;
		for (T recipe : recipes) {
			if (!jeasync$isSkippedRecipe(recipe)) {
				RecipeIngredientSupplier ingredientSupplier = addRecipe(recipeCategory, recipe, hiddenRecipes);
				if (ingredientSupplier != null) {
					if (deferLinks) {
						jeasync$markDeferred();
					} else {
						recipeTypeData.addFocusLinks(recipe, ingredientSupplier.getFocusLinks());
					}
					addedRecipes.add(recipe);
				}
			} else {
				skipped++;
			}
		}
		if (skipped > 0) {
			jeasync$markSkipped(recipeType, skipped);
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
		if (!(recipe instanceof Recipe<?> vanillaRecipe)) {
			return false;
		}
		List<String> skipped = AsyncConfig.skippedRecipeSerializers();
		if (skipped.isEmpty()) {
			return false;
		}
		ResourceLocation serializerId = ForgeRegistries.RECIPE_SERIALIZERS.getKey(vanillaRecipe.getSerializer());
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
