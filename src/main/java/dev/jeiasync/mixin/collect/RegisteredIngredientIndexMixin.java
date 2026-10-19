package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.api.ingredients.subtypes.UidContext;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Keeps the registered-ingredient index intact when plugins scan it and edit it together.
 * <p>
 * {@code getAllIngredients} hands out the live {@code LinkedHashMap} values. A fuel scan streams
 * that view while another plugin adds or removes an ingredient, and the iterator throws
 * {@code ConcurrentModificationException}. The same map is what runtime adds and removes write.
 * Two of those at once can loop inside the bucket update.
 * <p>
 * Lookups return a snapshot taken under the lock. The uid computation stays outside it.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(targets = "mezz.jei.library.ingredients.RegisteredIngredientIndex", remap = false)
public abstract class RegisteredIngredientIndexMixin<T> {
	@Shadow
	private IIngredientHelper<T> ingredientHelper;

	@Shadow
	private Map<Object, ITypedIngredient<T>> ingredientsByUid;

	@Shadow
	private Map<Object, List<ITypedIngredient<T>>> ingredientsByGroupingUid;

	@Shadow
	@Nullable
	private Object getUid(ITypedIngredient<T> ingredient) {
		throw new AssertionError();
	}

	@Shadow
	@Nullable
	private Object getGroupingUid(ITypedIngredient<T> ingredient) {
		throw new AssertionError();
	}

	@Shadow
	private void addToGroup(@Nullable Object groupingUid, ITypedIngredient<T> ingredient) {
		throw new AssertionError();
	}

	@Shadow
	private void replaceInGroup(@Nullable Object groupingUid, ITypedIngredient<T> previous, ITypedIngredient<T> replacement) {
		throw new AssertionError();
	}

	@Shadow
	private void removeFromGroup(@Nullable Object groupingUid, ITypedIngredient<T> ingredient) {
		throw new AssertionError();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the uid map write so a scan on another thread cannot tear the linked list.
	 */
	@Overwrite
	public void add(ITypedIngredient<T> ingredient) {
		dev.jeiasync.MixinApplied.mark("collect.RegisteredIngredientIndexMixin");
		@Nullable
		Object uid = getUid(ingredient);
		if (uid == null) {
			return;
		}
		@Nullable
		Object groupingUid = getGroupingUid(ingredient);
		synchronized (this) {
			ITypedIngredient<T> previous = ingredientsByUid.put(uid, ingredient);
			if (previous == null) {
				addToGroup(groupingUid, ingredient);
				return;
			}
			@Nullable
			Object previousGroupingUid = getGroupingUid(previous);
			if (Objects.equals(previousGroupingUid, groupingUid)) {
				replaceInGroup(groupingUid, previous, ingredient);
			} else {
				removeFromGroup(previousGroupingUid, previous);
				addToGroup(groupingUid, ingredient);
			}
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the uid map removal, and compute the grouping id after releasing it.
	 */
	@Overwrite
	public void remove(ITypedIngredient<T> ingredient) {
		@Nullable
		Object uid = getUid(ingredient);
		if (uid == null) {
			return;
		}
		ITypedIngredient<T> removed;
		synchronized (this) {
			removed = ingredientsByUid.remove(uid);
		}
		if (removed == null) {
			return;
		}
		// Grouping ids can walk tags or subtype data. The uid map is already updated, and
		// removeFromGroup matches the ingredient by identity, so this can wait until the lock is free.
		@Nullable
		Object groupingUid = getGroupingUid(removed);
		synchronized (this) {
			removeFromGroup(groupingUid, removed);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Read the uid map under the same lock that writes it.
	 */
	@Overwrite
	@Nullable
	public ITypedIngredient<T> getIngredientByUid(Object uid) {
		synchronized (this) {
			return ingredientsByUid.get(uid);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Search a snapshot so the legacy uid walk does not use the live iterator.
	 */
	@Overwrite
	public Optional<ITypedIngredient<T>> getByUid(String uid) {
		List<ITypedIngredient<T>> snapshot;
		synchronized (this) {
			ITypedIngredient<T> ingredient = ingredientsByUid.get(uid);
			if (ingredient != null) {
				return Optional.of(ingredient);
			}
			snapshot = new ArrayList<>(ingredientsByUid.values());
		}
		return snapshot.stream()
			.filter(typedIngredient -> uid.equals(ingredientHelper.getUniqueId(typedIngredient.getIngredient(), UidContext.Ingredient)))
			.findFirst();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Copy the group so a later add cannot throw while the caller walks it.
	 */
	@Overwrite
	public List<ITypedIngredient<T>> getIngredientsByGroupingUid(Object groupingUid) {
		synchronized (this) {
			List<ITypedIngredient<T>> ingredients = ingredientsByGroupingUid.get(groupingUid);
			if (ingredients == null) {
				return List.of();
			}
			return List.copyOf(ingredients);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Hand out a snapshot so a stream of every item does not walk the live linked list.
	 */
	@Overwrite
	public Collection<ITypedIngredient<T>> getAllIngredients() {
		synchronized (this) {
			return List.copyOf(ingredientsByUid.values());
		}
	}
}
