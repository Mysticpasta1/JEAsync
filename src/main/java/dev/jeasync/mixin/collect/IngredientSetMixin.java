package dev.jeasync.mixin.collect;

import mezz.jei.api.ingredients.IIngredientHelper;
import mezz.jei.api.ingredients.IIngredientType;
import mezz.jei.api.ingredients.subtypes.UidContext;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Makes {@link mezz.jei.library.ingredients.IngredientSet}'s accessors safe for concurrent
 * registration.
 * <p>
 * The backing map is a {@code LinkedHashMap} in a final field, so it cannot be swapped out, and
 * insertion order is significant here (it decides ingredient list ordering), so a snapshot has to
 * preserve it rather than use an unordered collection.
 * <p>
 * Every accessor here locks on {@code this}. {@link #iterator()} returns a snapshot rather than a live
 * view: a live view would let a caller iterate while another thread inserts, which throws
 * {@link java.util.ConcurrentModificationException}. Snapshotting loses fail-fast detection, which is
 * the deliberate trade.
 * <p>
 * <b>{@code add} is deliberately not overwritten.</b> Other add-ons patch JEI by injecting at the
 * call to this class's private {@code getUid} from inside {@code add} - AllTheLeaks does exactly that,
 * to null out the cached uid its {@code UidContext} would otherwise retain. Overwriting {@code add}
 * replaces that call site, so their injector has no target and their leak fix silently stops being
 * applied. The map write inside {@code add} is redirected onto the same monitor as {@code remove}.
 * Sending Runtime runs those two on different threads for one ingredient type, and a {@code LinkedHashMap}
 * that is mutated from both sides can loop inside its own bucket update.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.library.ingredients.IngredientSet.class, remap = false)
public abstract class IngredientSetMixin<V> {
	@Shadow
	private IIngredientHelper<V> ingredientHelper;

	@Shadow
	private Map<Object, V> ingredients;

	@Shadow
	private UidContext context;

	@Shadow
	@Nullable
	private Object getUid(V ingredient) {
		throw new AssertionError();
	}

	/**
	 * Keeps {@code getUid} in {@code add} so an injector aimed at that call still finds it.
	 * Only the map write takes the monitor, matching {@code remove}.
	 */
	@Redirect(
		method = "add",
		at = @At(
			value = "INVOKE",
			target = "Ljava/util/Map;put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;"
		)
	)
	private Object jeasync$putLocked(Map<Object, V> map, Object uid, V value) {
		synchronized (this) {
			return map.put(uid, value);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the uid map so a worker can remove an ingredient while another thread reads it.
	 */
	@Overwrite
	public boolean remove(Object value) {
		Object uid = getUid((V) value);
		synchronized (this) {
			return uid != null && ingredients.remove(uid) != null;
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock each removal so a bulk remove does not race a concurrent reader.
	 */
	@Overwrite
	public boolean removeAll(Collection<?> c) {
		Objects.requireNonNull(c);
		boolean modified = false;
		for (Object value : c) {
			modified |= remove(value);
		}
		return modified;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the uid lookup so a contains check does not race a concurrent insert.
	 */
	@Overwrite
	public boolean contains(Object o) {
		IIngredientType<V> ingredientType = ingredientHelper.getIngredientType();
		Class<? extends V> ingredientClass = ingredientType.getIngredientClass();
		if (!ingredientClass.isInstance(o)) {
			return false;
		}
		V v = ingredientClass.cast(o);
		Object uid = getUid(v);
		synchronized (this) {
			return uid != null && ingredients.containsKey(uid);
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the uid lookup so a get does not race a concurrent insert.
	 */
	@Overwrite
	public Optional<V> getByUid(String uid) {
		synchronized (this) {
			V v = ingredients.get(uid);
			if (v != null) {
				return Optional.of(v);
			}

			// Legacy fallback, exactly as upstream: older JEI versions keyed this map by the
			// string uid, so a save written by them still has to resolve.
			for (V ingredient : ingredients.values()) {
				String legacyUid = ingredientHelper.getUniqueId(ingredient, context);
				if (uid.equals(legacyUid)) {
					return Optional.of(ingredient);
				}
			}
			return Optional.empty();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the map clear so it does not race a concurrent reader.
	 */
	@Overwrite
	public void clear() {
		synchronized (this) {
			ingredients.clear();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Return a snapshot so iteration does not throw ConcurrentModificationException.
	 */
	@Overwrite
	public Iterator<V> iterator() {
		// Snapshot, preserving the LinkedHashMap's insertion order.
		synchronized (this) {
			return new ArrayList<>(ingredients.values()).iterator();
		}
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the size read so it does not race a concurrent insert.
	 */
	@Overwrite
	public int size() {
		synchronized (this) {
			return ingredients.size();
		}
	}
}
