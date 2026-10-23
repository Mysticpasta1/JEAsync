package dev.jeasync;

import mezz.jei.api.ingredients.ITypedIngredient;
import mezz.jei.common.ingredients.itemStacks.TypedItemStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shares one typed item across every plain recipe slot that names the same item.
 * <p>
 * A tagged stack is left to the original factory. In a large pack those tags are unique, so a map
 * of them keeps a second copy of every compound for the whole registration pass. An empty tag is
 * treated as no tag, so it shares the plain item instead of keeping an empty compound.
 * <p>
 * The map holds weak references, so a wrapper that nothing kept is collected. It is cleared when
 * startup finishes.
 * <p>
 * Mysticpasta1 — the tagged intern matched one key per typed stack and pinned that nbt copy.
 */
public final class TypedIngredientIntern {
	private static final ThreadLocal<Boolean> ENTERED = ThreadLocal.withInitial(() -> Boolean.FALSE);
	private static final ReferenceQueue<ITypedIngredient<ItemStack>> QUEUE = new ReferenceQueue<>();
	private static final ConcurrentHashMap<Item, Slot> PLAIN = new ConcurrentHashMap<>();
	private static volatile boolean logged;

	private TypedIngredientIntern() {
	}

	public static void clear() {
		PLAIN.clear();
		while (QUEUE.poll() != null) {
			// Drop the cleared references so a later load does not expunge into the new map.
		}
	}

	/**
	 * @return the shared wrapper, or null when the caller should run the original factory
	 */
	@Nullable
	public static ITypedIngredient<ItemStack> internStack(ItemStack stack) {
		if (stack == null || !JeiLoadProgress.isLoading() || ENTERED.get()) {
			return null;
		}
		CompoundTag tag = stack.getTag();
		if ((tag != null && !tag.isEmpty()) || stack.getCount() != 1) {
			return null;
		}
		return internPlain(stack.getItem(), () -> TypedItemStack.create(stack));
	}

	/**
	 * @return the shared wrapper, or null when the caller should run the original factory
	 */
	@Nullable
	public static ITypedIngredient<ItemStack> internItem(ItemLike itemLike) {
		if (itemLike == null || !JeiLoadProgress.isLoading() || ENTERED.get()) {
			return null;
		}
		Item item = itemLike.asItem();
		return internPlain(item, () -> TypedItemStack.create(itemLike));
	}

	@Nullable
	private static ITypedIngredient<ItemStack> internPlain(Item item, Factory factory) {
		expunge();
		Slot existing = PLAIN.get(item);
		if (existing != null) {
			ITypedIngredient<ItemStack> shared = existing.get();
			if (shared != null) {
				return shared;
			}
			PLAIN.remove(item, existing);
		}
		ITypedIngredient<ItemStack> created = create(factory);
		Slot slot = new Slot(item, created);
		Slot raced = PLAIN.putIfAbsent(item, slot);
		if (raced != null) {
			ITypedIngredient<ItemStack> shared = raced.get();
			if (shared != null) {
				return shared;
			}
			PLAIN.put(item, slot);
		}
		mark();
		return created;
	}

	private static ITypedIngredient<ItemStack> create(Factory factory) {
		ENTERED.set(Boolean.TRUE);
		try {
			return factory.create();
		} finally {
			ENTERED.set(Boolean.FALSE);
		}
	}

	private static void mark() {
		if (logged) {
			return;
		}
		logged = true;
		MixinApplied.mark("collect.TypedItemStackMixin");
		LogManager.getLogger(TypedIngredientIntern.class).info(
			"jeasync: sharing identical plain items so registration does not wrap every slot"
		);
	}

	private static void expunge() {
		Object cleared = QUEUE.poll();
		while (cleared instanceof Slot slot) {
			PLAIN.remove(slot.item, slot);
			cleared = QUEUE.poll();
		}
	}

	@FunctionalInterface
	private interface Factory {
		ITypedIngredient<ItemStack> create();
	}

	private static final class Slot extends WeakReference<ITypedIngredient<ItemStack>> {
		private final Item item;

		private Slot(Item item, ITypedIngredient<ItemStack> value) {
			super(value, QUEUE);
			this.item = item;
		}
	}
}
