package dev.jeiasync.mixin.collect;

import com.google.common.collect.ImmutableSetMultimap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Collections;
import java.util.Map;
import java.util.Set;

/**
 * Synchronization for SetMultiMap.
 * <p>
 * {@code get} and {@code toImmutable} reach into the backing map directly rather than going
 * through MultiMap, so they need their own locking.
 * <p>
 * That map is declared on MultiMap, not here, so it is reached through MultiMapAccessor rather
 * than shadowed: a mixin's @Shadow only matches fields declared in its own target.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.core.collect.SetMultiMap.class, remap = false)
public abstract class SetMultiMapMixin<K, V> {
	@Overwrite
	public Set<V> get(K key) {
		Map<K, Set<V>> map = jeiAsyncMap();
		Set<V> collection;
		synchronized (map) {
			collection = map.get(key);
		}
		if (collection != null) {
			synchronized (collection) {
				// Snapshot, not a view: see MultiMapMixin.get for why a live unmodifiable view
				// still throws ConcurrentModificationException at the caller's iteration site.
				return Set.copyOf(collection);
			}
		}
		return Collections.emptySet();
	}

	@Overwrite
	public ImmutableSetMultimap<K, V> toImmutable() {
		Map<K, Set<V>> map = jeiAsyncMap();
		ImmutableSetMultimap.Builder<K, V> builder = ImmutableSetMultimap.builder();
		synchronized (map) {
			map.forEach((key, collection) -> {
				synchronized (collection) {
					builder.putAll(key, collection);
				}
			});
		}
		return builder.build();
	}

	@SuppressWarnings("unchecked")
	private Map<K, Set<V>> jeiAsyncMap() {
		return (Map<K, Set<V>>) (Map<?, ?>) ((MultiMapAccessor) (Object) this).jeiAsyncBackingMap();
	}
}
