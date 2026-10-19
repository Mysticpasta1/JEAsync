package dev.jeiasync.client;

import dev.jeiasync.mixin.client.MinecraftClientAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the client handles plugins need during registration, through the accessor mixin, and only
 * from code that already knows it is on a client. {@link dev.jeiasync.WorkerEnvironment} reaches
 * this class by name so a dedicated server never loads it.
 */
public final class ClientStatePuller {
	private ClientStatePuller() {
	}

	public static @Nullable ClientHandles pull() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return null;
		}
		MinecraftClientAccessor accessor = (MinecraftClientAccessor) (Object) minecraft;
		ClientLevel level = accessor.jeiAsyncLevel();
		// Touch registry access here, on the thread that is wearing the game classloader, so the
		// first lookup is not a worker racing the render thread into the registry.
		if (level != null) {
			level.registryAccess();
		}
		return new ClientHandles(
			level,
			accessor.jeiAsyncPlayer(),
			accessor.jeiAsyncResourceManager(),
			accessor.jeiAsyncLanguageManager(),
			accessor.jeiAsyncItemColors(),
			accessor.jeiAsyncBlockColors(),
			accessor.jeiAsyncModelManager(),
			accessor.jeiAsyncTextureManager()
		);
	}

	public static boolean isClientGameThread() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return false;
		}
		Thread gameThread = ((MinecraftClientAccessor) (Object) minecraft).jeiAsyncGameThread();
		return Thread.currentThread() == gameThread;
	}

	/** Runs {@code task} on the client thread. A call already on that thread runs inline. */
	public static void schedule(Runnable task) {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.isSameThread()) {
			task.run();
		} else {
			minecraft.execute(task);
		}
	}
}
