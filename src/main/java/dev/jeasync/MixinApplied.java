package dev.jeasync;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Records which of this mod's mixins have actually been merged into their targets.
 * <p>
 * This exists because the obvious check is worthless: forcing the <em>target</em> class to load
 * with {@code Class.forName} succeeds whether or not a mixin was ever applied to it. That mistake
 * made an earlier version of {@link MixinSelfCheck} report "23 targets passed" on a build in which
 * no mixin had been applied at all.
 * <p>
 * A mixin records itself from inside the code it injects, so an entry can only appear here if the
 * mixin really did apply. Absence is therefore meaningful.
 */
public final class MixinApplied {
	private static final Set<String> APPLIED = ConcurrentHashMap.newKeySet();

	private MixinApplied() {
	}

	/** Called from inside injected mixin code. */
	public static void mark(String mixinName) {
		APPLIED.add(mixinName);
	}

	public static boolean isApplied(String mixinName) {
		return APPLIED.contains(mixinName);
	}

	public static int appliedCount() {
		return APPLIED.size();
	}

	public static Set<String> applied() {
		return Set.copyOf(APPLIED);
	}

	public static void clear() {
		APPLIED.clear();
	}
}