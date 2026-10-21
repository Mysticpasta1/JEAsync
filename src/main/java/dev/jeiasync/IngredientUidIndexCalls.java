package dev.jeiasync;

import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import org.apache.logging.log4j.LogManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.function.Supplier;

/**
 * Binds {@code IngredientUidIndex} methods without naming that class.
 * <p>
 * The index and its nested consumer are package-private. A mixin compiled in JEI's package would
 * export a package JEI already exports, which ModLauncher rejects. The handles live here, not in
 * the mixin, so their lookup does not run inside the index's own static initialiser. This class
 * also stays outside {@code dev.jeiasync.mixin}: Mixin refuses to load a non-mixin class from that
 * package once the merged JEI class references it.
 */
public final class IngredientUidIndexCalls {
	@FunctionalInterface
	public interface Call {
		Object run() throws Throwable;
	}

	private static volatile boolean ready;
	private static MethodHandle getOne;
	private static MethodHandle getPair;
	private static MethodHandle computeExact;
	private static MethodHandle computeGrouping;
	private static MethodHandle forEach;

	private IngredientUidIndexCalls() {
	}

	public static MethodHandle getOne() {
		ensure();
		return getOne;
	}

	public static MethodHandle getPair() {
		ensure();
		return getPair;
	}

	public static MethodHandle computeExact() {
		ensure();
		return computeExact;
	}

	public static MethodHandle computeGrouping() {
		ensure();
		return computeGrouping;
	}

	public static MethodHandle forEach() {
		ensure();
		return forEach;
	}

	/**
	 * The caller of {@code compute*IfAbsent} adds to the collection the supplier just created.
	 * That add happens after the index method returns, so the collection itself has to accept
	 * concurrent updates. An {@code ArrayList} stays an {@code ArrayList} because compaction calls
	 * {@code trimToSize} on it.
	 */
	/**
	 * Wraps a compute supplier. The lambda lives here, not in the mixin: a lambda compiled into
	 * the mixin keeps a reference to that class, and Mixin refuses to load it.
	 */
	public static Supplier<Object> guarding(Supplier<?> supplier) {
		return () -> guardValue(supplier.get());
	}

	public static Object guardValue(Object created) {
		if (created instanceof ArrayList<?> list && created.getClass() == ArrayList.class) {
			return new LockedArrayList(list);
		}
		if (created instanceof ObjectOpenHashSet<?> set) {
			return Collections.synchronizedSet(set);
		}
		return created;
	}

	@SuppressWarnings("unchecked")
	public static <E extends Throwable> void rethrow(Throwable throwable) throws E {
		throw (E) throwable;
	}

	private static void ensure() {
		if (ready) {
			return;
		}
		synchronized (IngredientUidIndexCalls.class) {
			if (ready) {
				return;
			}
			try {
				Class<?> index = Class.forName("mezz.jei.library.recipes.collect.IngredientUidIndex");
				Class<?> ingredientType = Class.forName("mezz.jei.api.ingredients.IIngredientType");
				Class<?> supplier = Class.forName("java.util.function.Supplier");
				Class<?> consumer = Class.forName("mezz.jei.library.recipes.collect.IngredientUidIndex$EntryConsumer");
				getOne = bind(index, "get", ingredientType, Object.class);
				getPair = bind(index, "get", ingredientType, Object.class, Object.class);
				computeExact = bind(index, "computeExactIfAbsent", ingredientType, Object.class, supplier);
				computeGrouping = bind(index, "computeGroupingIfAbsent", ingredientType, Object.class, supplier);
				forEach = bind(index, "forEach", consumer);
			} catch (Throwable t) {
				LogManager.getLogger().error("JEI Async: could not lock the recipe uid index", t);
			}
			ready = true;
		}
	}

	private static MethodHandle bind(Class<?> owner, String name, Class<?>... params) throws ReflectiveOperationException {
		Method method = owner.getDeclaredMethod(name, params);
		method.setAccessible(true);
		return MethodHandles.lookup().unreflect(method);
	}

	private static final class LockedArrayList extends ArrayList<Object> {
		private LockedArrayList(Collection<?> source) {
			// One slot until the row grows. Most ingredients appear in a handful of recipes, and a
			// floor of 8 keeps an empty array alive for every one of them until compact() runs.
			super(Math.max(source.size(), 1));
			for (Object element : source) {
				super.add(element);
			}
		}

		@Override
		public synchronized boolean add(Object element) {
			return super.add(element);
		}

		@Override
		public synchronized void add(int index, Object element) {
			super.add(index, element);
		}

		@Override
		public synchronized boolean addAll(Collection<?> collection) {
			return super.addAll(collection);
		}

		@Override
		public synchronized boolean addAll(int index, Collection<?> collection) {
			return super.addAll(index, collection);
		}

		@Override
		public synchronized Object remove(int index) {
			return super.remove(index);
		}

		@Override
		public synchronized boolean remove(Object element) {
			return super.remove(element);
		}

		@Override
		public synchronized void trimToSize() {
			super.trimToSize();
		}
	}
}
