package dev.jeiasync.api;

import mezz.jei.api.IModPlugin;

/**
 * Optional. Every plugin's JEI registration callbacks run off the main thread whether or not it
 * implements this, and one that fails off-thread is detected, demoted to the calling thread and
 * re-run there.
 * <p>
 * Implement it only to say something this add-on cannot infer - that your plugin is main-thread-only
 * regardless of what it throws, or that a particular load should not be parallelised. Overriding
 * {@link #canExecuteAsync()} to return false is the way to keep a plugin inline:
 * <ul>
 *     <li>{@code Minecraft.getInstance()} or any of its fields</li>
 *     <li>level or block state access, and tile entity queries</li>
 *     <li>player state</li>
 *     <li>GUI construction or rendering</li>
 * </ul>
 * The failure mode this cannot catch is a plugin that corrupts shared state without throwing; for
 * that, {@code -Djeiasync.requireOptIn=true} restricts off-thread registration to plugins that
 * implement this interface.
 * <p>
 * Note that this interface lives in the add-on rather than in JEI's own API, so a plugin that
 * implements it depends on this add-on being installed.
 */
public interface IAsyncCompatiblePlugin extends IModPlugin {
	/**
	 * @return false to keep this plugin's registration callbacks on the calling thread.
	 */
	default boolean canExecuteAsync() {
		return true;
	}
}