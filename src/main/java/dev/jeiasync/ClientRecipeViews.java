package dev.jeiasync;

/**
 * Reads of the client recipe manager taken while JEI is loading.
 * <p>
 * Mixin owns {@code dev.jeiasync.mixin}, so this interface stays in {@code dev.jeiasync}.
 */
public interface ClientRecipeViews {
	/**
	 * True when a plugin already copied a recipe map that a later sync has replaced.
	 */
	boolean jeiasync$servedStale();

	/** Forgets copies handed out by the pass that just finished. */
	void jeiasync$clearServed();
}
