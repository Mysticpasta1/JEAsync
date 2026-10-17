package dev.jeiasync.mixin.collect;

import com.google.common.collect.ImmutableTable;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Makes {@link mezz.jei.core.collect.Table} safe for concurrent plugin registration.
 * <p>
 * Upstream keeps rows in a plain {@code HashMap} and row maps in plain {@code HashMap}s, and
 * concurrent plugin registration writes both, so both levels need locking.
 * <p>
 * The row supplier is only reachable through the final {@code rowMappingFunction} field, which a
 * mixin cannot reassign, so the wrapping happens in {@link #getRow} instead: that is already the
 * single funnel every accessor goes through.
 * <p>
 * Targets upstream 15.32.0.173.
 */
@Mixin(value = mezz.jei.core.collect.Table.class, remap = false)
public abstract class TableMixin<R, C, V> {
	@Shadow
	private Map<R, Map<C, V>> table;

	@Shadow
	private Function<R, Map<C, V>> rowMappingFunction;

	@Overwrite
	public static <R, C, V> mezz.jei.core.collect.Table<R, C, V> hashBasedTable() {
		return new mezz.jei.core.collect.Table<>(Collections.synchronizedMap(new HashMap<>()), HashMap::new);
	}

	@Overwrite
	public static <R, C, V> mezz.jei.core.collect.Table<R, C, V> identityHashBasedTable() {
		return new mezz.jei.core.collect.Table<>(Collections.synchronizedMap(new IdentityHashMap<>()), IdentityHashMap::new);
	}

	@Overwrite
	public Map<C, V> getRow(R row) {
		return table.computeIfAbsent(row, key -> Collections.synchronizedMap(rowMappingFunction.apply(key)));
	}

	@Overwrite
	@Nullable
	public V get(R row, C col) {
		Map<C, V> rowMap = getRow(row);
		synchronized (rowMap) {
			return rowMap.get(col);
		}
	}

	@Overwrite
	public V computeIfAbsent(R row, C col, Supplier<V> valueSupplier) {
		Map<C, V> rowMap = getRow(row);
		synchronized (rowMap) {
			return rowMap.computeIfAbsent(col, k -> valueSupplier.get());
		}
	}

	@Overwrite
	@Nullable
	public V put(R row, C col, V val) {
		Map<C, V> rowMap = getRow(row);
		synchronized (rowMap) {
			return rowMap.put(col, val);
		}
	}

	@Overwrite
	public boolean contains(R row, C col) {
		Map<C, V> map = table.get(row);
		if (map == null) {
			return false;
		}
		synchronized (map) {
			return map.containsKey(col);
		}
	}

	@Overwrite
	public void clear() {
		synchronized (table) {
			table.clear();
		}
	}

	@Overwrite
	public ImmutableTable<R, C, V> toImmutable() {
		ImmutableTable.Builder<R, C, V> builder = ImmutableTable.builder();
		synchronized (table) {
			for (Map.Entry<R, Map<C, V>> entry : table.entrySet()) {
				R row = entry.getKey();
				Map<C, V> rowMap = entry.getValue();
				synchronized (rowMap) {
					for (Map.Entry<C, V> rowEntry : rowMap.entrySet()) {
						builder.put(row, rowEntry.getKey(), rowEntry.getValue());
					}
				}
			}
		}
		return builder.build();
	}
}