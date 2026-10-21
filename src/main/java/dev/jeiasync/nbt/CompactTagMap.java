package dev.jeiasync.nbt;

import net.minecraft.nbt.Tag;

import java.util.AbstractCollection;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Entry storage for a compound tag that usually holds only a few keys.
 * <p>
 * A {@link HashMap} spends a node object and an empty table slot on every key. Registration keeps
 * millions of compounds, and those nodes were the retained heap. Keys live in two arrays until the
 * ninth key, then in a {@link HashMap} held by this same object. The compound keeps this wrapper:
 * fastnbt calls {@code put} and {@code compute} on the map field, so swapping the field for a
 * {@link HashMap} would not change the map those calls already read.
 * <p>
 * Mysticpasta1 — the heap after the open-hash copy was tens of millions of {@code HashMap.Node}.
 */
public final class CompactTagMap implements Map<String, Tag> {
	/** Compounds wider than this keep a {@link HashMap}. Most tags in the dump were shorter. */
	private static final int MAX = 8;

	private static volatile Class<?> openHashMapClass;

	private String[] keys;
	private Tag[] values;
	private int size;
	private HashMap<String, Tag> wide;

	/** An empty tag that is about to receive its first key. */
	public CompactTagMap() {
		this.keys = new String[1];
		this.values = new Tag[1];
	}

	private CompactTagMap(int ignored) {
	}

	/**
	 * Copies {@code source} so the compound can drop the map it was given. An empty source stays
	 * the caller's concern: the compound uses one shared empty map for that.
	 */
	public static CompactTagMap copyOf(Map<String, Tag> source) {
		int count = source.size();
		CompactTagMap map = new CompactTagMap(0);
		if (count > MAX) {
			map.wide = new HashMap<>(hashCapacity(count));
			map.wide.putAll(source);
			return map;
		}
		if (count == 0) {
			map.keys = new String[1];
			map.values = new Tag[1];
			return map;
		}
		String[] copiedKeys = new String[count];
		Tag[] copiedValues = new Tag[count];
		int index = 0;
		for (Entry<String, Tag> entry : source.entrySet()) {
			String key = entry.getKey();
			if (key == null) {
				throw new NullPointerException("compound key");
			}
			copiedKeys[index] = key;
			copiedValues[index] = entry.getValue();
			index++;
		}
		map.keys = copiedKeys;
		map.values = copiedValues;
		map.size = index;
		return map;
	}

	/** The pack's compound map, matched by name once and then by the class itself. */
	public static boolean isOpenHashMap(Map<?, ?> map) {
		Class<?> type = map.getClass();
		Class<?> known = openHashMapClass;
		if (known != null) {
			return type == known;
		}
		if ("it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap".equals(type.getName())) {
			openHashMapClass = type;
			return true;
		}
		return false;
	}

	@Override
	public int size() {
		HashMap<String, Tag> wideMap = this.wide;
		return wideMap != null ? wideMap.size() : this.size;
	}

	@Override
	public boolean isEmpty() {
		return size() == 0;
	}

	@Override
	public boolean containsKey(Object key) {
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			return wideMap.containsKey(key);
		}
		return indexOf(key) >= 0;
	}

	@Override
	public boolean containsValue(Object value) {
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			return wideMap.containsValue(value);
		}
		Tag[] local = this.values;
		int count = this.size;
		for (int i = 0; i < count; i++) {
			if (Objects.equals(local[i], value)) {
				return true;
			}
		}
		return false;
	}

	@Override
	public Tag get(Object key) {
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			return wideMap.get(key);
		}
		int index = indexOf(key);
		return index >= 0 ? this.values[index] : null;
	}

	@Override
	public Tag put(String key, Tag value) {
		if (key == null) {
			throw new NullPointerException("compound key");
		}
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			return wideMap.put(key, value);
		}
		int found = indexOf(key);
		if (found >= 0) {
			Tag old = this.values[found];
			this.values[found] = value;
			return old;
		}
		int count = this.size;
		if (count >= MAX) {
			return promoteAndPut(key, value);
		}
		String[] localKeys = this.keys;
		if (localKeys == null || count == localKeys.length) {
			int next = localKeys == null || localKeys.length == 0 ? 1 : Math.min(MAX, localKeys.length << 1);
			if (next <= count) {
				next = Math.min(MAX, count + 1);
			}
			this.keys = localKeys == null ? new String[next] : Arrays.copyOf(localKeys, next);
			this.values = this.values == null ? new Tag[next] : Arrays.copyOf(this.values, next);
			localKeys = this.keys;
		}
		localKeys[count] = key;
		this.values[count] = value;
		this.size = count + 1;
		return null;
	}

	@Override
	public Tag remove(Object key) {
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			return wideMap.remove(key);
		}
		int index = indexOf(key);
		if (index < 0) {
			return null;
		}
		return removeAt(index);
	}

	@Override
	public void putAll(Map<? extends String, ? extends Tag> other) {
		for (Entry<? extends String, ? extends Tag> entry : other.entrySet()) {
			put(entry.getKey(), entry.getValue());
		}
	}

	@Override
	public void clear() {
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			wideMap.clear();
			return;
		}
		int count = this.size;
		for (int i = 0; i < count; i++) {
			this.keys[i] = null;
			this.values[i] = null;
		}
		this.size = 0;
	}

	@Override
	public void forEach(BiConsumer<? super String, ? super Tag> action) {
		Objects.requireNonNull(action);
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			wideMap.forEach(action);
			return;
		}
		int count = this.size;
		String[] localKeys = this.keys;
		Tag[] localValues = this.values;
		for (int i = 0; i < count; i++) {
			action.accept(localKeys[i], localValues[i]);
		}
	}

	@Override
	public Set<String> keySet() {
		return new KeySet();
	}

	@Override
	public Collection<Tag> values() {
		return new ValueView();
	}

	@Override
	public Set<Entry<String, Tag>> entrySet() {
		return new EntrySet();
	}

	@Override
	public boolean equals(Object other) {
		if (other == this) {
			return true;
		}
		if (!(other instanceof Map<?, ?> map)) {
			return false;
		}
		if (map.size() != size()) {
			return false;
		}
		try {
			HashMap<String, Tag> wideMap = this.wide;
			if (wideMap != null) {
				return wideMap.equals(map);
			}
			int count = this.size;
			for (int i = 0; i < count; i++) {
				String key = this.keys[i];
				Tag value = this.values[i];
				if (value == null) {
					if (map.get(key) != null || !map.containsKey(key)) {
						return false;
					}
				} else if (!value.equals(map.get(key))) {
					return false;
				}
			}
			return true;
		} catch (ClassCastException | NullPointerException ignored) {
			return false;
		}
	}

	@Override
	public int hashCode() {
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			return wideMap.hashCode();
		}
		int hash = 0;
		int count = this.size;
		for (int i = 0; i < count; i++) {
			hash += this.keys[i].hashCode() ^ Objects.hashCode(this.values[i]);
		}
		return hash;
	}

	private Tag promoteAndPut(String key, Tag value) {
		HashMap<String, Tag> map = new HashMap<>(hashCapacity(this.size + 1));
		int count = this.size;
		for (int i = 0; i < count; i++) {
			map.put(this.keys[i], this.values[i]);
		}
		Tag previous = map.put(key, value);
		this.keys = null;
		this.values = null;
		this.size = 0;
		this.wide = map;
		return previous;
	}

	private Tag removeAt(int index) {
		Tag old = this.values[index];
		int last = --this.size;
		this.keys[index] = this.keys[last];
		this.values[index] = this.values[last];
		this.keys[last] = null;
		this.values[last] = null;
		return old;
	}

	private int indexOf(Object key) {
		if (!(key instanceof String string)) {
			return -1;
		}
		String[] local = this.keys;
		int count = this.size;
		for (int i = 0; i < count; i++) {
			String candidate = local[i];
			if (candidate == string || candidate.equals(string)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Copies every value with {@link Tag#copy()}. Compound copy uses this so the new tag never
	 * holds the map the caller built for the copy.
	 */
	public static CompactTagMap deepCopy(Map<String, Tag> source) {
		if (source instanceof CompactTagMap compact) {
			return compact.deepCopySelf();
		}
		int count = source.size();
		CompactTagMap map = new CompactTagMap(0);
		if (count > MAX) {
			map.wide = new HashMap<>(hashCapacity(count));
			for (Entry<String, Tag> entry : source.entrySet()) {
				map.wide.put(copiedKey(entry.getKey()), copiedValue(entry.getValue()));
			}
			return map;
		}
		if (count == 0) {
			map.keys = new String[1];
			map.values = new Tag[1];
			return map;
		}
		String[] copiedKeys = new String[count];
		Tag[] copiedValues = new Tag[count];
		int index = 0;
		for (Entry<String, Tag> entry : source.entrySet()) {
			copiedKeys[index] = copiedKey(entry.getKey());
			copiedValues[index] = copiedValue(entry.getValue());
			index++;
		}
		map.keys = copiedKeys;
		map.values = copiedValues;
		map.size = index;
		return map;
	}

	private CompactTagMap deepCopySelf() {
		HashMap<String, Tag> wideMap = this.wide;
		if (wideMap != null) {
			return deepCopy(wideMap);
		}
		int count = this.size;
		CompactTagMap map = new CompactTagMap(0);
		if (count == 0) {
			map.keys = new String[1];
			map.values = new Tag[1];
			return map;
		}
		String[] copiedKeys = new String[count];
		Tag[] copiedValues = new Tag[count];
		for (int i = 0; i < count; i++) {
			copiedKeys[i] = this.keys[i];
			copiedValues[i] = copiedValue(this.values[i]);
		}
		map.keys = copiedKeys;
		map.values = copiedValues;
		map.size = count;
		return map;
	}

	private static String copiedKey(String key) {
		if (key == null) {
			throw new NullPointerException("compound key");
		}
		return key;
	}

	private static Tag copiedValue(Tag value) {
		return value == null ? null : value.copy();
	}

	/** Initial capacity that holds {@code entries} without a resize at the default load factor. */
	private static int hashCapacity(int entries) {
		return entries + (entries >>> 1) + 1;
	}

	private final class KeySet extends AbstractSet<String> {
		@Override
		public Iterator<String> iterator() {
			HashMap<String, Tag> wideMap = CompactTagMap.this.wide;
			if (wideMap != null) {
				return wideMap.keySet().iterator();
			}
			return new KeyIterator();
		}

		@Override
		public int size() {
			return CompactTagMap.this.size();
		}

		@Override
		public boolean contains(Object key) {
			return containsKey(key);
		}

		@Override
		public boolean remove(Object key) {
			if (!containsKey(key)) {
				return false;
			}
			CompactTagMap.this.remove(key);
			return true;
		}
	}

	private final class KeyIterator implements Iterator<String> {
		private int cursor;
		private int last = -1;

		@Override
		public boolean hasNext() {
			return this.cursor < CompactTagMap.this.size && CompactTagMap.this.wide == null;
		}

		@Override
		public String next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			this.last = this.cursor++;
			return CompactTagMap.this.keys[this.last];
		}

		@Override
		public void remove() {
			if (this.last < 0) {
				throw new IllegalStateException();
			}
			removeAt(this.last);
			this.cursor = this.last;
			this.last = -1;
		}
	}

	private final class ValueView extends AbstractCollection<Tag> {
		@Override
		public Iterator<Tag> iterator() {
			HashMap<String, Tag> wideMap = CompactTagMap.this.wide;
			if (wideMap != null) {
				return wideMap.values().iterator();
			}
			return new ValueIterator();
		}

		@Override
		public int size() {
			return CompactTagMap.this.size();
		}
	}

	private final class ValueIterator implements Iterator<Tag> {
		private int cursor;
		private int last = -1;

		@Override
		public boolean hasNext() {
			return this.cursor < CompactTagMap.this.size && CompactTagMap.this.wide == null;
		}

		@Override
		public Tag next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			this.last = this.cursor++;
			return CompactTagMap.this.values[this.last];
		}

		@Override
		public void remove() {
			if (this.last < 0) {
				throw new IllegalStateException();
			}
			removeAt(this.last);
			this.cursor = this.last;
			this.last = -1;
		}
	}

	private final class EntrySet extends AbstractSet<Entry<String, Tag>> {
		@Override
		public Iterator<Entry<String, Tag>> iterator() {
			HashMap<String, Tag> wideMap = CompactTagMap.this.wide;
			if (wideMap != null) {
				return wideMap.entrySet().iterator();
			}
			return new EntryIterator();
		}

		@Override
		public int size() {
			return CompactTagMap.this.size();
		}

		@Override
		public boolean contains(Object other) {
			if (!(other instanceof Entry<?, ?> entry)) {
				return false;
			}
			Object key = entry.getKey();
			if (!containsKey(key)) {
				return false;
			}
			return Objects.equals(get(key), entry.getValue());
		}

		@Override
		public boolean remove(Object other) {
			if (!contains(other)) {
				return false;
			}
			CompactTagMap.this.remove(((Entry<?, ?>) other).getKey());
			return true;
		}
	}

	private final class EntryIterator implements Iterator<Entry<String, Tag>> {
		private int cursor;
		private int last = -1;

		@Override
		public boolean hasNext() {
			return this.cursor < CompactTagMap.this.size && CompactTagMap.this.wide == null;
		}

		@Override
		public Entry<String, Tag> next() {
			if (!hasNext()) {
				throw new NoSuchElementException();
			}
			this.last = this.cursor++;
			return new AbstractMapEntry(CompactTagMap.this.keys[this.last], CompactTagMap.this.values[this.last]);
		}

		@Override
		public void remove() {
			if (this.last < 0) {
				throw new IllegalStateException();
			}
			removeAt(this.last);
			this.cursor = this.last;
			this.last = -1;
		}
	}

	/** Snapshot of one pair. Iteration order is not part of the tag contract. */
	private static final class AbstractMapEntry implements Entry<String, Tag> {
		private final String key;
		private final Tag value;

		private AbstractMapEntry(String key, Tag value) {
			this.key = key;
			this.value = value;
		}

		@Override
		public String getKey() {
			return this.key;
		}

		@Override
		public Tag getValue() {
			return this.value;
		}

		@Override
		public Tag setValue(Tag value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public boolean equals(Object other) {
			if (!(other instanceof Entry<?, ?> entry)) {
				return false;
			}
			return Objects.equals(this.key, entry.getKey()) && Objects.equals(this.value, entry.getValue());
		}

		@Override
		public int hashCode() {
			return this.key.hashCode() ^ Objects.hashCode(this.value);
		}
	}
}
