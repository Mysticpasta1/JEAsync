package dev.jeiasync.mixin.load;

import com.google.common.base.Preconditions;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientRenderer;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.common.util.ErrorUtil;
import mezz.jei.library.ingredients.IngredientInfo;
import mezz.jei.library.load.registration.IngredientManagerBuilder;
import net.minecraft.resources.ResourceLocation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Keeps the ingredient-type map intact when every plugin registers at once.
 * <p>
 * {@code ingredientInfos} is a {@code LinkedHashMap}. Two plugins calling {@code put} together
 * tear its links, so {@code size()} and a later walk disagree. {@code RegisteredIngredients} then
 * builds its type list with {@code stream().toList()}, which trusts that size and throws
 * {@code End size N is less than fixed size M}.
 * <p>
 * The typed-ingredient scan stays outside the lock. Only the duplicate check and the map write
 * share it. The registering plugin id is kept per thread, because the builder has one field and
 * many plugins pass through {@code registerIngredients} together.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = IngredientManagerBuilder.class, remap = false)
public abstract class IngredientManagerBuilderMixin {
	@Shadow
	private LinkedHashMap<IIngredientType<?>, IngredientInfo<?>> ingredientInfos;

	@Shadow
	private static <V> List<ITypedIngredient<V>> createTypedIngredients(
		IIngredientType<V> ingredientType,
		Collection<V> ingredients,
		IIngredientHelper<V> ingredientHelper,
		String context
	) {
		throw new AssertionError();
	}

	@Unique
	private static ThreadLocal<ResourceLocation> jeiasync$pluginUid;

	@Unique
	private static ThreadLocal<ResourceLocation> jeiasync$uid() {
		ThreadLocal<ResourceLocation> local = jeiasync$pluginUid;
		if (local != null) {
			return local;
		}
		synchronized (IngredientManagerBuilderMixin.class) {
			local = jeiasync$pluginUid;
			if (local == null) {
				local = new ThreadLocal<>();
				jeiasync$pluginUid = local;
			}
			return local;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Remember the registering plugin on this thread so parallel registration records the right id.
	 */
	@Overwrite
	public void registerIngredients(IModPlugin plugin) {
		ThreadLocal<ResourceLocation> uids = jeiasync$uid();
		ResourceLocation previous = uids.get();
		uids.set(plugin.getPluginUid());
		try {
			plugin.registerIngredients((IngredientManagerBuilder) (Object) this);
		} finally {
			if (previous == null) {
				uids.remove();
			} else {
				uids.set(previous);
			}
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the type map write so parallel registration cannot tear the linked list.
	 */
	@Overwrite
	public <V> void register(
		IIngredientType<V> ingredientType,
		Collection<V> allIngredients,
		IIngredientHelper<V> ingredientHelper,
		IIngredientRenderer<V> ingredientRenderer
	) {
		ErrorUtil.checkNotNull(ingredientType, "ingredientType");
		ErrorUtil.checkNotNull(allIngredients, "allIngredients");
		ErrorUtil.checkNotNull(ingredientHelper, "ingredientHelper");
		ErrorUtil.checkNotNull(ingredientRenderer, "ingredientRenderer");
		Preconditions.checkArgument(ingredientRenderer.getWidth() == 16,
			"the default ingredient renderer registered here will be used for drawing " +
				"ingredients in the ingredient list, and it must have a width of 16"
		);
		Preconditions.checkArgument(ingredientRenderer.getHeight() == 16,
			"the default ingredient renderer registered here will be used for drawing " +
				"ingredients in the ingredient list, and it must have a height of 16"
		);

		List<ITypedIngredient<V>> typedIngredients = createTypedIngredients(
			ingredientType,
			allIngredients,
			ingredientHelper,
			"during ingredient registration"
		);
		synchronized (ingredientInfos) {
			if (ingredientInfos.containsKey(ingredientType)) {
				throw new IllegalArgumentException("Ingredient type has already been registered: " + ingredientType.getUid());
			}
			ingredientInfos.put(ingredientType, new IngredientInfo<>(
				ingredientType,
				typedIngredients,
				ingredientHelper,
				ingredientRenderer,
				jeiasync$uid().get()
			));
		}
	}
}
