package dev.jeasync.api;

import mezz.jei.api.IModPlugin;

/**
 * Opt out of off-thread JEI registration.
 * <p>
 * Every plugin is dispatched. Implement this and return false from {@link #canExecuteAsync()} to
 * stay on the calling thread. That is for a callback that touches GL directly. A callback that
 * enters Minecraft's event loop while the client thread is blocked is failed and re-run on the
 * client thread without this interface.
 * <p>
 * This is not part of JEI's API. Implementing it depends on this add-on.
 */
public interface IAsyncCompatiblePlugin extends IModPlugin {
	/**
	 * @return false to keep this plugin's registration callbacks on the calling thread
	 */
	default boolean canExecuteAsync() {
		return true;
	}
}