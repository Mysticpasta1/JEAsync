package dev.jeiasync;

/**
 * Lock for the block-to-item map.
 * <p>
 * Forge stores that map as a plain {@link java.util.HashMap}. Category registration and creative
 * tab builds read it from the worker pool while a plugin may still register a block item. The
 * readers and writers in this mod share this lock.
 */
public final class BlockItemMapLock {
	public static final Object LOCK = new Object();

	private BlockItemMapLock() {
	}
}
