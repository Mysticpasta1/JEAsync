package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import mezz.jei.api.ingredients.subtypes.IIngredientSubtypeInterpreter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;
import java.util.Optional;

/**
 * Makes {@link mezz.jei.library.ingredients.subtypes.SubtypeInterpreters} safe for concurrent
 * plugin registration.
 * <p>
 * The backing map is a plain {@code IdentityHashMap}. {@code addInterpreter} writes to it during
 * registration, while {@code contains} and {@code get} are called for every ingredient whose uid is
 * computed, which happens for every ingredient of every recipe being registered. Concurrent writes
 * and reads on an {@code IdentityHashMap} can corrupt its internal table, so all three methods take
 * the map's monitor.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.ingredients.subtypes.SubtypeInterpreters.class, remap = false)
public abstract class SubtypeInterpretersMixin {
	@Shadow
	private Map<Object, IIngredientSubtypeInterpreter<?>> map;

	@Overwrite
	public <B, I> boolean addInterpreter(IIngredientTypeWithSubtypes<B, I> type, B base, IIngredientSubtypeInterpreter<I> interpreter) {
		if (!type.getIngredientBaseClass().isInstance(base)) {
			throw new IllegalArgumentException(
				String.format("base must be instance of %s but got %s instead", type.getIngredientBaseClass(), base.getClass())
			);
		}
		synchronized (map) {
			if (map.containsKey(base)) {
				return false;
			}
			map.put(base, interpreter);
			return true;
		}
	}

	@Overwrite
	public <B, I> Optional<IIngredientSubtypeInterpreter<I>> get(IIngredientTypeWithSubtypes<B, I> type, I ingredient) {
		B base = type.getBase(ingredient);
		synchronized (map) {
			@SuppressWarnings("unchecked")
			IIngredientSubtypeInterpreter<I> cast = (IIngredientSubtypeInterpreter<I>) map.get(base);
			return Optional.ofNullable(cast);
		}
	}

	@Overwrite
	public <B, T> boolean contains(IIngredientTypeWithSubtypes<B, T> type, T ingredient) {
		B base = type.getBase(ingredient);
		synchronized (map) {
			return map.containsKey(base);
		}
	}
}