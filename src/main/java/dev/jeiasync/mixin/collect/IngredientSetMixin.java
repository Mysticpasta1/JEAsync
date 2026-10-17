package dev.jeiasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Makes {@link mezz.jei.library.ingredients.IngredientSet} safe for concurrent ingredient
 * registration.
 * <p>
 * The backing map is a {@code LinkedHashMap} in a final field, so it cannot be swapped out, and
 * insertion order is significant here (it decides ingredient list ordering), so a snapshot has to
 * preserve it rather than use an unordered collection.
 * <p>
 * Every accessor locks on {@code this}. {@link #iterator()} returns a snapshot rather than a live
 * view: a live view would let a caller iterate while another thread inserts, which throws
 * {@link java.util.ConcurrentModificationException}. Snapshotting loses fail-fast detection, which
 * is the deliberate trade.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.library.ingredients.IngredientSet.class, remap = false)
public abstract class IngredientSetMixin<V> {
	@Shadow
	private IIngredientHelper<V> ingredientHelper;

	@Shadow
	private Map<String, V> ingredients;

	@Shadow
	@Nullable
	private String getUid(V ingredient) {
		throw new AssertionError();
	}

	@Overwrite
	public boolean add(V value) {
		String uid = getUid(value);
		synchronized (this) {
			return uid != null && ingredients.put(uid, value) == null;
		}
	}

	@Overwrite
	public boolean remove(Object value) {
		String uid = getUid((V) value);
		synchronized (this) {
			return uid != null && ingredients.remove(uid) != null;
		}
	}

	@Overwrite
	public boolean removeAll(Collection<?> c) {
		Objects.requireNonNull(c);
		boolean modified = false;
		for (Object value : c) {
			modified |= remove(value);
		}
		return modified;
	}

	@Overwrite
	public boolean contains(Object o) {
		IIngredientType<V> ingredientType = ingredientHelper.getIngredientType();
		Class<? extends V> ingredientClass = ingredientType.getIngredientClass();
		if (!ingredientClass.isInstance(o)) {
			return false;
		}
		V v = ingredientClass.cast(o);
		String uid = getUid(v);
		synchronized (this) {
			return uid != null && ingredients.containsKey(uid);
		}
	}

	@Overwrite
	public Optional<V> getByUid(String uid) {
		synchronized (this) {
			return Optional.ofNullable(ingredients.get(uid));
		}
	}

	@Overwrite
	public void clear() {
		synchronized (this) {
			ingredients.clear();
		}
	}

	@Overwrite
	public Iterator<V> iterator() {
		// Snapshot, preserving the LinkedHashMap's insertion order.
		synchronized (this) {
			return new ArrayList<>(ingredients.values()).iterator();
		}
	}

	@Overwrite
	public int size() {
		synchronized (this) {
			return ingredients.size();
		}
	}
}