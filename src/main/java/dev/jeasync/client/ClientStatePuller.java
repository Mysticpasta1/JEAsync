package dev.jeasync.client;

import dev.jeasync.mixin.client.MinecraftClientAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;

/**
 * Reads the client handles plugins need during registration, through the accessor mixin, and only
 * from code that already knows it is on a client. {@link dev.jeasync.WorkerEnvironment} reaches
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

	/**
	 * Publishes the handles pulled on the game thread into Minecraft's public fields, so code that
	 * reads those fields directly sees them on a worker thread.
	 */
	public static void installFields(Object handles) {
		if (!(handles instanceof ClientHandles clientHandles)) {
			return;
		}
		handles = clientHandles;
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft == null) {
			return;
		}
		MinecraftClientAccessor accessor = (MinecraftClientAccessor) (Object) minecraft;
		if (clientHandles.level != null) {
			accessor.jeiAsyncSetLevel(clientHandles.level);
		}
		if (clientHandles.player != null) {
			accessor.jeiAsyncSetPlayer(clientHandles.player);
		}
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
