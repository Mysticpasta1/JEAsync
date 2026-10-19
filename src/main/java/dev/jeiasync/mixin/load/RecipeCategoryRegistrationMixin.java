package dev.jeiasync.mixin.load;

import com.google.common.base.Preconditions;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.library.runtime.JeiHelpers;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Keeps category registration intact when every plugin registers at once.
 * <p>
 * The category list and the type-uid map are plain collections. Two plugins passing the
 * "not yet registered" check together, then both calling {@code addAll}, drop entries. The
 * recipe map is built from whatever survived, so a plugin that logged a successful category
 * phase later fails with "no recipe category registered".
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.load.registration.RecipeCategoryRegistration.class, remap = false)
public abstract class RecipeCategoryRegistrationMixin {
	@Shadow
	private List<IRecipeCategory<?>> recipeCategories;

	@Shadow
	private Map<ResourceLocation, RecipeType<?>> recipeTypes;

	@Shadow
	private JeiHelpers jeiHelpers;

	/**
	 * @author Mysticpasta1
	 * @reason Register categories under one lock so parallel plugins keep every category and its type uid.
	 */
	@Overwrite
	public void addRecipeCategories(IRecipeCategory<?>... recipeCategories) {
		dev.jeiasync.MixinApplied.mark("load.RecipeCategoryRegistrationMixin");
		ErrorUtil.checkNotEmpty(recipeCategories, "recipeCategories");
		synchronized (this) {
			for (IRecipeCategory<?> recipeCategory : recipeCategories) {
				RecipeType<?> recipeType = recipeCategory.getRecipeType();
				Preconditions.checkNotNull(recipeType, "Recipe type cannot be null %s", recipeCategory);
				ResourceLocation recipeTypeUid = recipeType.getUid();
				if (recipeTypes.containsKey(recipeTypeUid)) {
					RecipeType<?> existing = recipeTypes.get(recipeTypeUid);
					throw new IllegalArgumentException("Tried to register a recipe type \"" + recipeType + "\" but there is already one registered with the same UID: " + existing);
				}
				recipeTypes.put(recipeTypeUid, recipeType);
				Preconditions.checkArgument(recipeCategory.getWidth() > 0, "Width must be greater than 0");
				Preconditions.checkArgument(recipeCategory.getHeight() > 0, "Height must be greater than 0");
			}
			Collections.addAll(this.recipeCategories, recipeCategories);
			// A snapshot, so a lookup streaming the published collection does not walk the live list.
			this.jeiHelpers.setRecipeCategories(List.copyOf(this.recipeCategories));
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Copy the category list under the same lock that adds to it.
	 */
	@Overwrite
	public List<IRecipeCategory<?>> getRecipeCategories() {
		synchronized (this) {
			return List.copyOf(recipeCategories);
		}
	}
}
