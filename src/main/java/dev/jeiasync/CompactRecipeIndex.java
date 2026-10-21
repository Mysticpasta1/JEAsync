package dev.jeiasync;

import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import mezz.jei.api.ingredients.IIngredientType;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.RandomAccess;

/**
 * Ingredient-to-recipe links stored as recipe ids on the ingredient maps.
 * <p>
 * A uid that points at one recipe stores that id in the map value. An array is allocated only when
 * a second recipe shares the uid. A lookup copies the recipe references for that one ingredient.
 * The recipe objects themselves stay in the category list, because a category still has to draw them.
 * <p>
 * Mysticpasta1 — packing those ids into one byte buffer copied every link again and grew the heap.
 */
public final class CompactRecipeIndex<R> {
	private static final Object NULL_UID = new Object();
	private static final long MISSING = 0L;
	private static final long MULTI_BIT = 1L << 33;

	private static volatile boolean logged;

	private final Object lock = new Object();
	private final ArrayList<R> byId = new ArrayList<>();
	private @Nullable IdentityHashMap<R, Integer> toId = new IdentityHashMap<>();
	private final Map<IIngredientType<?>, Object2LongOpenHashMap<Object>> open = new HashMap<>();
	private final ArrayList<Multi> multis = new ArrayList<>();

	public void add(IIngredientType<?> ingredientType, Object uid, R recipe) {
		if (recipe == null) {
			return;
		}
		synchronized (lock) {
			int id = idOf(recipe);
			Object2LongOpenHashMap<Object> rows = rows(ingredientType);
			Object key = key(uid);
			long current = rows.getLong(key);
			if (current == MISSING) {
				rows.put(key, id + 1L);
				return;
			}
			if ((current & MULTI_BIT) == 0L) {
				Multi multi = new Multi((int) current - 1, id);
				rows.put(key, MULTI_BIT | multis.size());
				multis.add(multi);
				return;
			}
			multis.get((int) current).add(id);
		}
	}

	public List<R> get(IIngredientType<?> ingredientType, Object uid) {
		synchronized (lock) {
			Object2LongOpenHashMap<Object> rows = open.get(ingredientType);
			if (rows == null) {
				return List.of();
			}
			long current = rows.getLong(key(uid));
			if (current == MISSING) {
				return List.of();
			}
			if ((current & MULTI_BIT) == 0L) {
				return freezeOne((int) current - 1);
			}
			Multi multi = multis.get((int) current);
			return freeze(multi.data, multi.size);
		}
	}

	/**
	 * Drops spare slots in the maps. Safe to call again. Does not copy the links into another structure.
	 */
	public void compact() {
		synchronized (lock) {
			byId.trimToSize();
			for (Multi multi : multis) {
				multi.trim();
			}
			multis.trimToSize();
			for (Object2LongOpenHashMap<Object> rows : open.values()) {
				rows.trim();
			}
			// Rebuilt on the next add. Registration is finished, so this map does not have to stay.
			this.toId = null;
			if (!logged) {
				logged = true;
				LogManager.getLogger(CompactRecipeIndex.class).info(
					"JEI Async: keeping recipe links in the ingredient maps"
				);
			}
		}
	}

	private int idOf(R recipe) {
		IdentityHashMap<R, Integer> ids = this.toId;
		if (ids == null) {
			ids = new IdentityHashMap<>(Math.max(byId.size(), 1));
			for (int i = 0; i < byId.size(); i++) {
				ids.put(byId.get(i), i);
			}
			this.toId = ids;
		}
		Integer existing = ids.get(recipe);
		if (existing != null) {
			return existing;
		}
		int id = byId.size();
		byId.add(recipe);
		ids.put(recipe, id);
		return id;
	}

	private Object2LongOpenHashMap<Object> rows(IIngredientType<?> ingredientType) {
		Object2LongOpenHashMap<Object> rows = open.get(ingredientType);
		if (rows == null) {
			rows = newMap(1024);
			open.put(ingredientType, rows);
		}
		return rows;
	}

	private List<R> freezeOne(int id) {
		Object[] recipes = new Object[] { byId.get(id) };
		return new Frozen<>(recipes);
	}

	private List<R> freeze(int[] data, int count) {
		if (count == 0) {
			return List.of();
		}
		Object[] recipes = new Object[count];
		for (int i = 0; i < count; i++) {
			recipes[i] = byId.get(data[i]);
		}
		return new Frozen<>(recipes);
	}

	private static Object2LongOpenHashMap<Object> newMap(int capacity) {
		Object2LongOpenHashMap<Object> map = new Object2LongOpenHashMap<>(Math.max(capacity, 1));
		map.defaultReturnValue(MISSING);
		return map;
	}

	private static Object key(Object uid) {
		return uid == null ? NULL_UID : uid;
	}

	/**
	 * Immutable snapshot of one ingredient's recipes. Callers can iterate it without copying again.
	 */
	public static final class Frozen<R> extends AbstractList<R> implements RandomAccess {
		private final Object[] recipes;

		private Frozen(Object[] recipes) {
			this.recipes = recipes;
		}

		@Override
		public int size() {
			return recipes.length;
		}

		@Override
		@SuppressWarnings("unchecked")
		public R get(int index) {
			return (R) recipes[index];
		}
	}

	private static final class Multi {
		private int[] data;
		private int size;

		private Multi(int first, int second) {
			this.data = new int[] { first, second };
			this.size = 2;
		}

		private void add(int id) {
			if (size == data.length) {
				data = Arrays.copyOf(data, data.length << 1);
			}
			data[size++] = id;
		}

		private void trim() {
			if (data.length != size) {
				data = Arrays.copyOf(data, Math.max(size, 1));
			}
		}
	}
}
