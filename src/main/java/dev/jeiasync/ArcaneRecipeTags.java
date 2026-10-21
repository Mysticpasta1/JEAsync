package dev.jeiasync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the encoded spell and affinity tags while arcane anvil recipes are built.
 * <p>
 * Each imbue recipe writes the same scroll tag onto a new stack for every spell level, and each
 * affinity recipe encodes the same ring again for every spell. The codec result depends on the
 * spell, so the written tag is copied onto the next stack.
 * <p>
 * Mysticpasta1 — arcane anvil registration repeated that encode for every weapon and every spell.
 */
public final class ArcaneRecipeTags {
	private static final String SPELL_KEY = "irons_spellbooks:spell_container";
	private static final String AFFINITY_KEY = "irons_spellbooks:affinity_data";
	private static final ConcurrentHashMap<SpellLevel, Tag> SPELLS = new ConcurrentHashMap<>();
	private static final ConcurrentHashMap<Identity, Tag> AFFINITY = new ConcurrentHashMap<>();
	private static final MethodHandle CREATE_SCROLL = findCreateScroll();
	private static final MethodHandle SET_AFFINITY = findSetAffinity();
	private static final MethodHandle AFFINITY_SPELL = findAffinitySpell();
	private static volatile boolean marked;

	private ArcaneRecipeTags() {
	}

	/** Replaces {@code ISpellContainer.createScrollContainer} inside the arcane anvil recipe class. */
	public static Object applyScroll(Object spell, int level, ItemStack stack) {
		if (spell == null || stack == null || CREATE_SCROLL == null) {
			return invokeScroll(spell, level, stack);
		}
		SpellLevel key = new SpellLevel(spell, level);
		Tag cached = SPELLS.get(key);
		if (cached != null) {
			mark();
			write(stack, SPELL_KEY, cached);
			return null;
		}
		Object result = invokeScroll(spell, level, stack);
		remember(stack, SPELL_KEY, key);
		return result;
	}

	/** Replaces {@code AffinityData.set} inside the arcane anvil recipe class. */
	public static void applyAffinity(ItemStack stack, Object data) {
		if (stack == null || data == null || SET_AFFINITY == null) {
			invokeAffinity(stack, data);
			return;
		}
		Object spell = affinitySpell(data);
		if (spell == null) {
			invokeAffinity(stack, data);
			return;
		}
		Identity key = new Identity(spell);
		Tag cached = AFFINITY.get(key);
		if (cached != null) {
			mark();
			write(stack, AFFINITY_KEY, cached);
			return;
		}
		invokeAffinity(stack, data);
		CompoundTag tag = stack.getTag();
		if (tag == null) {
			return;
		}
		Tag written = tag.get(AFFINITY_KEY);
		if (written != null) {
			AFFINITY.putIfAbsent(key, written.copy());
		}
	}

	/** Drops the tags when a load begins, so a reload encodes the current spells. */
	public static void clear() {
		SPELLS.clear();
		AFFINITY.clear();
	}

	private static void remember(ItemStack stack, String key, SpellLevel id) {
		CompoundTag tag = stack.getTag();
		if (tag == null) {
			return;
		}
		Tag written = tag.get(key);
		if (written != null) {
			SPELLS.putIfAbsent(id, written.copy());
		}
	}

	private static void write(ItemStack stack, String key, Tag cached) {
		stack.getOrCreateTag().put(key, cached.copy());
	}

	private static Object invokeScroll(Object spell, int level, ItemStack stack) {
		if (CREATE_SCROLL == null) {
			return null;
		}
		try {
			return CREATE_SCROLL.invoke(spell, level, stack);
		} catch (Throwable failure) {
			throw new IllegalStateException("createScrollContainer", failure);
		}
	}

	private static void invokeAffinity(ItemStack stack, Object data) {
		if (SET_AFFINITY == null) {
			return;
		}
		try {
			SET_AFFINITY.invoke(stack, data);
		} catch (Throwable failure) {
			throw new IllegalStateException("AffinityData.set", failure);
		}
	}

	private static Object affinitySpell(Object data) {
		if (AFFINITY_SPELL == null) {
			return null;
		}
		try {
			return AFFINITY_SPELL.invoke(data);
		} catch (Throwable failure) {
			return null;
		}
	}

	private static void mark() {
		if (marked) {
			return;
		}
		marked = true;
		LogManager.getLogger(ArcaneRecipeTags.class).info(
			"JEI Async: arcane anvil reuses the spell tag for each spell and level"
		);
	}

	private static MethodHandle findCreateScroll() {
		try {
			Class<?> spells = Class.forName("io.redspace.ironsspellbooks.api.spells.AbstractSpell");
			Class<?> containers = Class.forName("io.redspace.ironsspellbooks.api.spells.ISpellContainer");
			Method method = containers.getMethod("createScrollContainer", spells, int.class, ItemStack.class);
			return MethodHandles.publicLookup().unreflect(method);
		} catch (ReflectiveOperationException failure) {
			return null;
		}
	}

	private static MethodHandle findSetAffinity() {
		try {
			Class<?> data = Class.forName("io.redspace.ironsspellbooks.api.item.curios.AffinityData");
			Method method = data.getMethod("set", ItemStack.class, data);
			return MethodHandles.publicLookup().unreflect(method);
		} catch (ReflectiveOperationException failure) {
			return null;
		}
	}

	private static MethodHandle findAffinitySpell() {
		try {
			Class<?> data = Class.forName("io.redspace.ironsspellbooks.api.item.curios.AffinityData");
			Method method = data.getMethod("getSpell");
			return MethodHandles.publicLookup().unreflect(method);
		} catch (ReflectiveOperationException failure) {
			return null;
		}
	}

	private static final class SpellLevel {
		private final Object spell;
		private final int level;

		private SpellLevel(Object spell, int level) {
			this.spell = spell;
			this.level = level;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof SpellLevel key && this.spell == key.spell && this.level == key.level;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this.spell) * 31 + this.level;
		}
	}

	private static final class Identity {
		private final Object spell;

		private Identity(Object spell) {
			this.spell = spell;
		}

		@Override
		public boolean equals(Object other) {
			return other instanceof Identity key && this.spell == key.spell;
		}

		@Override
		public int hashCode() {
			return System.identityHashCode(this.spell);
		}
	}
}
