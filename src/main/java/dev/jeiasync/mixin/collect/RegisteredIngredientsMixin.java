package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Makes {@link mezz.jei.library.ingredients.RegisteredIngredients} safe for concurrent plugin
 * registration.
 * <p>
 * {@code typeToInfo} and {@code orderedTypes} are immutable after construction and need nothing.
 * The two class-lookup maps are a different matter: they are built with {@code Collectors.toMap},
 * so they are plain {@code HashMap}s, and {@code getIngredientType(Class)} and
 * {@code getIngredientTypeWithSubtypesFromBase} both *write to them lazily* when they resolve a
 * class by scanning. Plugins reach these lookups during registration, so an off-thread plugin and
 * anything else resolving types concurrently would corrupt the map.
 * <p>
 * Each map is guarded by its own monitor. The expensive fallback scan is repeated under the lock
 * rather than optimistically outside it: doing it outside would reintroduce the same race on the
 * subsequent {@code put}.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.ingredients.RegisteredIngredients.class, remap = false)
public abstract class RegisteredIngredientsMixin {
	@Shadow
	private List<IIngredientType<?>> orderedTypes;

	@Shadow
	private Map<Class<?>, IIngredientType<?>> classToType;

	@Shadow
	private Map<Class<?>, IIngredientTypeWithSubtypes<?, ?>> baseClassToType;

	@Overwrite
	public <V> Optional<IIngredientType<V>> getIngredientType(Class<? extends V> ingredientClass) {
		mezz.jei.common.util.ErrorUtil.checkNotNull(ingredientClass, "ingredientClass");
		synchronized (classToType) {
			@SuppressWarnings("unchecked")
			IIngredientType<V> ingredientType = (IIngredientType<V>) this.classToType.get(ingredientClass);
			if (ingredientType != null) {
				return Optional.of(ingredientType);
			}
			for (IIngredientType<?> type : this.orderedTypes) {
				if (type.getIngredientClass().isAssignableFrom(ingredientClass)) {
					@SuppressWarnings("unchecked")
					IIngredientType<V> castType = (IIngredientType<V>) type;
					this.classToType.put(ingredientClass, castType);
					return Optional.of(castType);
				}
			}
			return Optional.empty();
		}
	}

	@Overwrite
	public <I, B> Optional<IIngredientTypeWithSubtypes<B, I>> getIngredientTypeWithSubtypesFromBase(B baseIngredient) {
		Class<?> baseIngredientClass = baseIngredient.getClass();
		synchronized (baseClassToType) {
			@SuppressWarnings("unchecked")
			IIngredientTypeWithSubtypes<B, I> ingredientType =
				(IIngredientTypeWithSubtypes<B, I>) this.baseClassToType.get(baseIngredientClass);
			if (ingredientType != null) {
				return Optional.of(ingredientType);
			}
			for (IIngredientType<?> type : this.orderedTypes) {
				if (type instanceof IIngredientTypeWithSubtypes<?, ?> typeWithSubtypes) {
					if (typeWithSubtypes.getIngredientBaseClass().isInstance(baseIngredient)) {
						@SuppressWarnings("unchecked")
						IIngredientTypeWithSubtypes<B, I> castType = (IIngredientTypeWithSubtypes<B, I>) typeWithSubtypes;
						this.baseClassToType.put(baseIngredientClass, castType);
						return Optional.of(castType);
					}
				}
			}
			return Optional.empty();
		}
	}
}