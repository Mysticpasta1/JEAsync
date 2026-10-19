package dev.jeiasync.mixin.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.ReloadableResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the client fields registration needs without going through a getter that may later grow a
 * game-thread check. Mixin adds this interface to {@link Minecraft}; the cast only satisfies javac.
 * <p>
 * Client only. A dedicated server has no {@code Minecraft} class to add the interface to.
 */
@Mixin(value = Minecraft.class, remap = false)
public interface MinecraftClientAccessor {
	@Accessor("level")
	ClientLevel jeiAsyncLevel();

	@Accessor("player")
	LocalPlayer jeiAsyncPlayer();

	@Accessor("gameThread")
	Thread jeiAsyncGameThread();

	@Accessor("resourceManager")
	ReloadableResourceManager jeiAsyncResourceManager();

	@Accessor("languageManager")
	LanguageManager jeiAsyncLanguageManager();

	@Accessor("itemColors")
	ItemColors jeiAsyncItemColors();

	@Accessor("blockColors")
	BlockColors jeiAsyncBlockColors();

	@Accessor("modelManager")
	ModelManager jeiAsyncModelManager();

	@Accessor("textureManager")
	TextureManager jeiAsyncTextureManager();
}
