package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientTypeWithSubtypes;
import mezz.jei.api.ingredients.subtypes.IIngredientSubtypeInterpreter;
import mezz.jei.api.ingredients.subtypes.ISubtypeInterpreter;
import mezz.jei.library.ingredients.subtypes.LegacyInterpreterAdapter;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Map;

/**
 * Makes {@link mezz.jei.library.ingredients.subtypes.SubtypeInterpreters} safe for concurrent
 * plugin registration.
 * <p>
 * The backing map is a plain {@code IdentityHashMap}. {@code addInterpreter} writes to it during
 * registration, while {@code contains} and {@code get} are called for every ingredient whose uid is
 * computed, which happens for every ingredient of every recipe being registered. Concurrent writes
 * and reads on an {@code IdentityHashMap} can corrupt its internal table, so every method takes the
 * map's monitor - including the check-then-put in {@code addInterpreter}, which is a
 * read-modify-write on the map and so has to be atomic rather than two separately-locked steps.
 * <p>
 * Targets upstream 19.69.0.456.
 */
@Mixin(value = mezz.jei.library.ingredients.subtypes.SubtypeInterpreters.class, remap = false)
public abstract class SubtypeInterpretersMixin {
	@Shadow
	private Map<Object, ISubtypeInterpreter<?>> map;

	/**
	 * @author Mysticpasta1
	 * @reason Lock interpreter registration for the legacy subtype adapter.
	 */
	@Overwrite
	public <B, I> boolean addInterpreter(IIngredientTypeWithSubtypes<B, I> type, B base, IIngredientSubtypeInterpreter<I> interpreter) {
		return addInterpreter(type, base, new LegacyInterpreterAdapter<>(interpreter));
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock interpreter registration so workers can add subtypes together.
	 */
	@Overwrite
	public <B, I> boolean addInterpreter(IIngredientTypeWithSubtypes<B, I> type, B base, ISubtypeInterpreter<I> interpreter) {
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

	/**
	 * @author Mysticpasta1
	 * @reason Lock the interpreter lookup so a read does not race registration.
	 */
	@Overwrite
	@Nullable
	public <B, I> ISubtypeInterpreter<I> get(IIngredientTypeWithSubtypes<B, I> type, I ingredient) {
		B base = type.getBase(ingredient);
		return getFromBase(type, base);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the base lookup so a read does not race registration.
	 */
	@Overwrite
	@Nullable
	public <B, I> ISubtypeInterpreter<I> getFromBase(IIngredientTypeWithSubtypes<B, I> type, B ingredientBase) {
		synchronized (map) {
			ISubtypeInterpreter<?> interpreter = map.get(ingredientBase);
			@SuppressWarnings("unchecked")
			ISubtypeInterpreter<I> cast = (ISubtypeInterpreter<I>) interpreter;
			return cast;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the interpreter lookup so a contains check does not race registration.
	 */
	@Overwrite
	public <B, T> boolean contains(IIngredientTypeWithSubtypes<B, T> type, T ingredient) {
		B base = type.getBase(ingredient);
		synchronized (map) {
			return map.containsKey(base);
		}
	}
}
