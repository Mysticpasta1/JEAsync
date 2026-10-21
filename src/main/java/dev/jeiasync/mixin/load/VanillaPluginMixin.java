package dev.jeiasync.mixin.load;

import dev.jeiasync.WorkerEnvironment;
import mezz.jei.library.plugins.vanilla.VanillaPlugin;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = VanillaPlugin.class, remap = false)
public abstract class VanillaPluginMixin {
	private static final Logger JEI_ASYNC_LOGGER = LogManager.getLogger();

	@Redirect(
		method = {"registerAdvanced", "registerRecipes"},
		at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;", opcode = org.objectweb.asm.Opcodes.GETFIELD),
		remap = true
	)
	private ClientLevel jeiAsyncLevel(Minecraft minecraft) {
		ClientLevel level = null;
		Object handles = WorkerEnvironment.registrationHandles();
		if (handles != null) {
			try {
				level = (ClientLevel) handles.getClass().getField("level").get(handles);
			} catch (ReflectiveOperationException ignored) {
			}
		}
		if (level == null) {
			Object snapshot = WorkerEnvironment.clientLevelSnapshot();
			if (snapshot instanceof ClientLevel clientLevel) {
				level = clientLevel;
			}
		}
		if (level == null) {
			level = minecraft.level;
		}
		if (level == null) {
			JEI_ASYNC_LOGGER.warn("JEI Async: VanillaPlugin could not resolve minecraft.level (handles={}, snapshot={})",
				handles != null, WorkerEnvironment.clientLevelSnapshot() != null);
		}
		return level;
	}
}
