package dev.jeasync.client;

/**
 * Heartbeat for the server recipe packet's client-side decode.
 * <p>
 * Mysticpasta1 — the second join decoded the packet for well over a minute under load, and the
 * tick-start hold expired into the fallback while the packet was still in flight. JEI's late-sync
 * restart then ran a second full registration pass on top of the first. The recipes packet
 * constructor stamps progress while it reads, so a hold can tell "packet still in flight" from
 * "packet never came".
 */
public final class RecipeSyncProgress {
	private static volatile long activityMs;

	private RecipeSyncProgress() {
	}

	/** Decode made progress by one recipe. */
	public static void stamp() {
		activityMs = System.currentTimeMillis();
	}

	/** The last decode progress time, zero when nothing decoded this session. */
	public static long lastActivityMs() {
		return activityMs;
	}
}
