package dev.jeiasync.mixin.client;

import dev.jeiasync.MixinApplied;
import dev.jeiasync.WorkerEnvironment;
import dev.jeiasync.client.ClientHandles;
import net.minecraft.client.Minecraft;
import net.minecraft.client.color.block.BlockColors;
import net.minecraft.client.color.item.ItemColors;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.texture.TextureManager;
import net.minecraft.client.resources.language.LanguageManager;
import net.minecraft.client.resources.model.ModelManager;
import net.minecraft.server.packs.resources.ResourceManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * While a plugin callback is running off the client thread, the getters below return the handles
 * pulled before the callback was dispatched. Outside that scope nothing here runs, so the client
 * thread keeps reading its own fields.
 * <p>
 * {@code level} and {@code player} are public fields. Call sites that read the fields directly are
 * not redirected; they observe the same objects the accessors pulled, because the pull stores those
 * references rather than copies.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftClientMixin {
	@Inject(method = "getResourceManager", at = @At("HEAD"), cancellable = true)
	private void jeiasync$resourceManager(CallbackInfoReturnable<ResourceManager> cir) {
		ClientHandles handles = handles();
		if (handles != null) {
			cir.setReturnValue(handles.resourceManager);
		}
	}

	@Inject(method = "getLanguageManager", at = @At("HEAD"), cancellable = true)
	private void jeiasync$languageManager(CallbackInfoReturnable<LanguageManager> cir) {
		ClientHandles handles = handles();
		if (handles != null) {
			cir.setReturnValue(handles.languageManager);
		}
	}

	// Forge adds this getter. It has no vanilla mapping, so it must not be remapped.
	@Inject(method = "getItemColors", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$itemColors(CallbackInfoReturnable<ItemColors> cir) {
		ClientHandles handles = handles();
		if (handles != null) {
			cir.setReturnValue(handles.itemColors);
		}
	}

	@Inject(method = "getBlockColors", at = @At("HEAD"), cancellable = true)
	private void jeiasync$blockColors(CallbackInfoReturnable<BlockColors> cir) {
		ClientHandles handles = handles();
		if (handles != null) {
			cir.setReturnValue(handles.blockColors);
		}
	}

	@Inject(method = "getModelManager", at = @At("HEAD"), cancellable = true)
	private void jeiasync$modelManager(CallbackInfoReturnable<ModelManager> cir) {
		ClientHandles handles = handles();
		if (handles != null) {
			cir.setReturnValue(handles.modelManager);
		}
	}

	@Inject(method = "getTextureManager", at = @At("HEAD"), cancellable = true)
	private void jeiasync$textureManager(CallbackInfoReturnable<TextureManager> cir) {
		ClientHandles handles = handles();
		if (handles != null) {
			cir.setReturnValue(handles.textureManager);
		}
	}

	@Inject(method = "getConnection", at = @At("HEAD"), cancellable = true)
	private void jeiasync$connection(CallbackInfoReturnable<ClientPacketListener> cir) {
		ClientHandles handles = handles();
		if (handles == null) {
			return;
		}
		LocalPlayer player = handles.player;
		cir.setReturnValue(player == null ? null : player.connection);
	}

	private static boolean marked;

	private static ClientHandles handles() {
		if (!marked) {
			marked = true;
			MixinApplied.mark("client.MinecraftClientMixin");
		}
		Object pulled = WorkerEnvironment.registrationHandles();
		if (pulled instanceof ClientHandles handles) {
			return handles;
		}
		return null;
	}
}
