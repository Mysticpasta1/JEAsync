package dev.jeiasync.client;

import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.jetbrains.annotations.Nullable;

/**
 * Client objects a registering plugin reads, taken from the game thread before the callback moves.
 * <p>
 * The references are the live ones. What the pull changes is <em>when</em> they are first read:
 * on a thread that already has the game classloader, rather than as a side effect of a worker
 * walking into {@code Minecraft} and initialising whatever that touches.
 */
public final class ClientHandles {
	public final @Nullable ClientLevel level;
	public final @Nullable LocalPlayer player;
	public final ResourceManager resourceManager;
	public final LanguageManager languageManager;
	public final ItemColors itemColors;
	public final BlockColors blockColors;
	public final ModelManager modelManager;
	public final TextureManager textureManager;

	public ClientHandles(
		@Nullable ClientLevel level,
		@Nullable LocalPlayer player,
		ResourceManager resourceManager,
		LanguageManager languageManager,
		ItemColors itemColors,
		BlockColors blockColors,
		ModelManager modelManager,
		TextureManager textureManager
	) {
		this.level = level;
		this.player = player;
		this.resourceManager = resourceManager;
		this.languageManager = languageManager;
		this.itemColors = itemColors;
		this.blockColors = blockColors;
		this.modelManager = modelManager;
		this.textureManager = textureManager;
	}
}
