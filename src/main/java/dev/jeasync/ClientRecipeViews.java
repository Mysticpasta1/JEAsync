package dev.jeasync;

/**
 * Reads of the client recipe manager taken while JEI is loading.
 * <p>
 * Mixin owns {@code dev.jeasync.mixin}, so this interface stays in {@code dev.jeasync}.
 */
public interface ClientRecipeViews {
	/**
	 * True when a plugin already copied a recipe map that a later sync has replaced.
	 */
	boolean jeasync$servedStale();

	/** Forgets copies handed out by the pass that just finished. */
	void jeasync$clearServed();
}
