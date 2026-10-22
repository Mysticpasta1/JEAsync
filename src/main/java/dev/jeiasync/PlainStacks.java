package dev.jeiasync;

import org.apache.logging.log4j.LogManager;

/**
 * Builds stacks that skip the Forge throwaway {@code Damage:0} compound while active.
 * <p>
 * The constructor of a damageable stack writes a compound holding {@code Damage:0} before anyone
 * asked for damage data. That compound survives on every stack that never records real damage:
 * the grindstone scan builds tens of millions of plain stacks, each stamped and then either
 * replaced by an enchantment tag or kept as an output with no damage to show. With the flag on,
 * {@code ItemStackMixin} skips only that constructor write. A stack with no tag reads
 * {@code Damage} as 0, so the built stacks behave exactly like the constructor's own.
 * <p>
 * Mysticpasta1 — the heap said the retained complexity of JEI's grindstone recipes was the damage
 * and enchantment compounds that every built stack carried alone.
 */
public final class PlainStacks {
	private static final ThreadLocal<int[]> DEPTH = ThreadLocal.withInitial(() -> new int[1]);
	private static volatile boolean marked;

	private PlainStacks() {
	}

	public static void enter() {
		int[] depth = DEPTH.get();
		depth[0]++;
		if (depth[0] == 1) {
			mark();
		}
	}

	public static void exit() {
		int[] depth = DEPTH.get();
		if (depth[0] > 0) {
			depth[0]--;
		}
	}

	/**
	 * Whether stacks built on this thread skip the constructor damage write.
	 */
	public static boolean active() {
		return DEPTH.get()[0] > 0;
	}

	private static void mark() {
		if (marked) {
			return;
		}
		marked = true;
		LogManager.getLogger(PlainStacks.class).info(
			"JEI Async: grindstone stacks are built without the throwaway damage compound"
		);
	}
}
