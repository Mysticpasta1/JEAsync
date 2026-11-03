package dev.jeasync.mixin.load;

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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;

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
 * A plugin that already inserted a type may pass through {@code register} again when its first
 * attempt threw later in the same callback and the phase runs it a second time. That second call
 * keeps the type it already published. A type owned by a different plugin still throws.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = IngredientManagerBuilder.class, remap = false)
public abstract class IngredientManagerBuilderMixin {
	@Unique
	private static final Logger JEI_ASYNC_LOGGER = LogManager.getLogger();

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
	private static ThreadLocal<ResourceLocation> jeasync$pluginUid;

	@Unique
	private static ThreadLocal<ResourceLocation> jeasync$uid() {
		ThreadLocal<ResourceLocation> local = jeasync$pluginUid;
		if (local != null) {
			return local;
		}
		synchronized (IngredientManagerBuilderMixin.class) {
			local = jeasync$pluginUid;
			if (local == null) {
				local = new ThreadLocal<>();
				jeasync$pluginUid = local;
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
		ThreadLocal<ResourceLocation> uids = jeasync$uid();
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

		ResourceLocation currentPlugin = jeasync$uid().get();
		// A retry has already paid for this list once. Skip the scan when this plugin owns the type.
		if (jeasync$registeredBy(ingredientType, currentPlugin)) {
			return;
		}

		List<ITypedIngredient<V>> typedIngredients = createTypedIngredients(
			ingredientType,
			allIngredients,
			ingredientHelper,
			"during ingredient registration"
		);
		synchronized (ingredientInfos) {
			if (jeasync$registeredBy(ingredientType, currentPlugin)) {
				return;
			}
			if (ingredientInfos.containsKey(ingredientType)) {
				throw new IllegalArgumentException("Ingredient type has already been registered: " + ingredientType.getUid());
			}
			ingredientInfos.put(ingredientType, new IngredientInfo<>(
				ingredientType,
				typedIngredients,
				ingredientHelper,
				ingredientRenderer,
				currentPlugin
			));
		}
	}

	/**
	 * True when {@code currentPlugin} already published this type. A null id never matches, so a
	 * call that arrived outside {@code registerIngredients} still hits the duplicate check.
	 */
	@Unique
	private boolean jeasync$registeredBy(IIngredientType<?> ingredientType, ResourceLocation currentPlugin) {
		if (currentPlugin == null) {
			return false;
		}
		synchronized (ingredientInfos) {
			IngredientInfo<?> existing = ingredientInfos.get(ingredientType);
			if (existing == null) {
				return false;
			}
			Optional<ResourceLocation> owner = existing.getRegisteringPluginUid();
			boolean same = owner.isPresent() && currentPlugin.equals(owner.get());
			if (same) {
				JEI_ASYNC_LOGGER.info("jeasync: ingredient type {} is already registered by {}, so this call is skipped",
					ingredientType.getUid(), currentPlugin);
			}
			return same;
		}
	}
}
