package dev.jeiasync;

import mezz.jei.api.IModPlugin;

/**
 * A plugin whose off-thread attempt failed.
 * Kept outside {@code dev.jeiasync.mixin} so the merged JEI class can load it.
 */
public final class FailedPlugin {
	public final IModPlugin plugin;
	public final boolean onClient;

	public FailedPlugin(IModPlugin plugin, boolean onClient) {
		this.plugin = plugin;
		this.onClient = onClient;
	}
}
