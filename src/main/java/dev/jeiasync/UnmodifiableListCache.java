package dev.jeiasync;

import java.util.Collections;
import java.util.List;

/**
 * A one-slot memo for {@code Collections.unmodifiableList}.
 * <p>
 * {@code Collections.unmodifiableList(list)} is a pure function of {@code list}: it returns a fresh
 * immutable wrapper that delegates to the same live list. Calling it twice on one list produces two
 * interchangeable objects, so for a repeated lookup on the same list the second wrapper is pure
 * garbage.
 * <p>
 * One slot, rather than a {@code Map<List, List>}, is deliberate. The wrapper holds a strong
 * reference back to the list it wraps, so in a map keyed on that list the value keeps the key
 * alive - a {@code WeakHashMap} variant would never collect an entry, and any variant would grow by
 * one entry per uid ever looked up, which is the opposite of what this add-on is for. A single slot
 * replaces at most one wrapper and holds at most one list alive.
 * <p>
 * The slot is a single field of an immutable pair behind a {@code volatile}, so a reader either sees
 * the whole previous pair or none of it. Two separate fields would not do: this is reachable from
 * the render thread and from registration threads, and a mismatched read would hand back a wrapper
 * around the wrong list.
 * <p>
 * Instances are created lazily by the owning mixin, so two threads racing on first use may each
 * build one; both start empty and are equivalent, and the losing instance is collected.
 */
public final class UnmodifiableListCache {
	private volatile Slot slot;

	/**
	 * Returns an unmodifiable view of {@code list}, reusing the previous wrapper when it wraps this
	 * same list. The result is a live view either way, so a list mutated after the call is still
	 * reflected in it.
	 */
	public List<?> get(List<?> list) {
		Slot current = this.slot;
		if (current != null && current.source == list) {
			return current.wrapper;
		}
		List<?> wrapper = Collections.unmodifiableList(list);
		this.slot = new Slot(list, wrapper);
		return wrapper;
	}

	private static final class Slot {
		private final List<?> source;
		private final List<?> wrapper;

		private Slot(List<?> source, List<?> wrapper) {
			this.source = source;
			this.wrapper = wrapper;
		}
	}
}