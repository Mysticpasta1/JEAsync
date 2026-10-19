package dev.jeiasync.mixin.collect;

import com.google.common.collect.ImmutableListMultimap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Synchronization for ListMultiMap.
 * <p>
 * {@code get} and {@code toImmutable} reach into the backing map directly rather than going
 * through MultiMap, so they need their own locking.
 * <p>
 * That map is declared on MultiMap, not here, so it is reached through MultiMapAccessor rather
 * than shadowed: a mixin's @Shadow only matches fields declared in its own target.
 * <p>
 * Targets upstream 15.71.0.224.
 */
@Mixin(value = mezz.jei.common.collect.ListMultiMap.class, remap = false)
public abstract class ListMultiMapMixin<K, V> {
	/**
	 * @author Mysticpasta1
	 * @reason Lock the map and copy the list so the caller does not iterate a live collection.
	 */
	@Overwrite
	public List<V> get(K key) {
		Map<K, List<V>> map = jeiAsyncMap();
		List<V> list;
		synchronized (map) {
			list = map.get(key);
		}
		if (list != null) {
			synchronized (list) {
				// Snapshot, not a view: see MultiMapMixin.get for why a live unmodifiable view
				// still throws ConcurrentModificationException at the caller's iteration site.
				return List.copyOf(list);
			}
		}
		return Collections.emptyList();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Lock the map while copying so the immutable snapshot is consistent.
	 */
	@Overwrite
	public ImmutableListMultimap<K, V> toImmutable() {
		Map<K, List<V>> map = jeiAsyncMap();
		ImmutableListMultimap.Builder<K, V> builder = ImmutableListMultimap.builder();
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
	private Map<K, List<V>> jeiAsyncMap() {
		return (Map<K, List<V>>) (Map<?, ?>) ((MultiMapAccessor) (Object) this).jeiAsyncBackingMap();
	}
}
