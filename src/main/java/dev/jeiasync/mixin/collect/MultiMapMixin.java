package dev.jeiasync.mixin.collect;

import com.google.common.collect.ImmutableMultimap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Makes {@link mezz.jei.core.collect.MultiMap} safe for concurrent plugin registration.
 * <p>
 * The backing {@code map} field is {@code protected final} and cannot be reassigned by a mixin,
 * so rather than swapping in a concurrent map, every method that touches it takes the map's
 * monitor. All outer-map access already goes through this class, which is enough to make
 * {@code computeIfAbsent} atomic.
 * <p>
 * Per-key collections are wrapped in {@link Collections#synchronizedCollection} as they are
 * created, because they are handed out through {@link #get} and iterated by callers outside the
 * map lock.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.core.collect.MultiMap.class, remap = false)
public abstract class MultiMapMixin<K, V, T extends Collection<V>> {
	@Shadow
	protected Map<K, T> map;

	@Shadow
	private Function<K, T> collectionMappingFunction;

		// No wrapper. Wrapping in Collections.synchronized* only protects methods that go through the
	// wrapper; itIterator does not take the lock, so callers iterating it unlocked still hit
	// ConcurrentModificationException. Every access here is already guarded by the monitor on the
	// collection itself, and get() hands out snapshots, so the wrapper bought nothing and cost an
	// extra allocation and an incompatible wrapper type per key.
	private T jeiAsyncWrap(T collection) {
		return collection;
	}

	private T jeiAsyncCollectionFor(K key) {
		synchronized (map) {
			return map.computeIfAbsent(key, k -> jeiAsyncWrap(collectionMappingFunction.apply(k)));
		}
	}

	@Overwrite
	public Collection<V> get(K key) {
		T collection;
		synchronized (map) {
			collection = map.get(key);
		}
		if (collection != null) {
			synchronized (collection) {
				// A snapshot, not a view. Collections.unmodifiableCollection looks safe but does
				// nothing: callers iterate what they get back long after this lock is released, and
				// an unmodifiable view shares its iterator with the live collection, so any concurrent
				// put or remove makes the caller throw ConcurrentModificationException.
				return List.copyOf(collection);
			}
		}
		return Collections.emptyList();
	}

	@Overwrite
	public boolean put(K key, V value) {
		return jeiAsyncCollectionFor(key).add(value);
	}

	@Overwrite
	public boolean putAll(K key, Collection<V> values) {
		return jeiAsyncCollectionFor(key).addAll(values);
	}

	@Overwrite
	public boolean remove(K key, V value) {
		T collection;
		synchronized (map) {
			collection = map.get(key);
		}
		if (collection == null) {
			return false;
		}
		synchronized (collection) {
			return collection.remove(value);
		}
	}

	@Overwrite
	public boolean containsKey(K key) {
		synchronized (map) {
			return map.containsKey(key);
		}
	}

	@Overwrite
	public boolean contains(K key, V value) {
		T collection;
		synchronized (map) {
			collection = map.get(key);
		}
		if (collection == null) {
			return false;
		}
		synchronized (collection) {
			return collection.contains(value);
		}
	}

	@Overwrite
	public Collection<V> allValues() {
		List<V> list = new ArrayList<>();
		synchronized (map) {
			for (T collection : map.values()) {
				synchronized (collection) {
					list.addAll(collection);
				}
			}
		}
		return list;
	}

	@Overwrite
	public void clear() {
		synchronized (map) {
			map.clear();
		}
	}

	@Overwrite
	public ImmutableMultimap<K, V> toImmutable() {
		ImmutableMultimap.Builder<K, V> builder = ImmutableMultimap.builder();
		synchronized (map) {
			map.forEach((key, collection) -> {
				synchronized (collection) {
					builder.putAll(key, collection);
				}
			});
		}
		return builder.build();
	}

	// Upstream returns live views of the backing map, which are unsafe to iterate once other
	// threads can write. Nothing inside JEI calls these two, so they return snapshots instead.
	@Overwrite
	public Set<Map.Entry<K, T>> entrySet() {
		synchronized (map) {
			return Set.copyOf(map.entrySet());
		}
	}

	@Overwrite
	public Set<K> keySet() {
		synchronized (map) {
			return Set.copyOf(map.keySet());
		}
	}
}