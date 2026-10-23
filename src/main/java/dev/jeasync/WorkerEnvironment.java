package dev.jeasync;

import net.minecraftforge.fml.loading.FMLEnvironment;
import net.minecraftforge.fml.util.thread.SidedThreadGroups;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;

/**
 * Context a worker needs in order to run the same plugin and service code the game thread would.
 * <p>
 * Two things are thread-local and silently wrong when copied from whichever thread happened to
 * submit the task. {@code ForkJoinPool} workers, and Forge's mod-loading workers, carry the
 * application classloader. Forge's event transformer and {@code ServiceLoader.load(Class)} both
 * consult that loader, so a plugin that loads a class or a platform service off-thread either
 * fails to build its event handlers or initialises the service as missing and poisons the class
 * for the rest of the session.
 * <p>
 * The loader that can see Minecraft and every mod is the one that defined Minecraft, which on
 * Forge is the transforming classloader. It is resolved from that class, not from the submitter.
 * Client handles plugins read while registering (level, player, resource manager, colors, models)
 * are pulled through accessors on the thread that still owns them, then installed for the
 * registration callback so a worker does not re-enter half-initialised client state to get them.
 */
public final class WorkerEnvironment {
	private static final Logger LOGGER = LogManager.getLogger();

	private static volatile ClassLoader gameLoader;
	private static volatile boolean announcedLoader;
	private static volatile Object clientSnapshot;
	private static volatile boolean jeiServicePulled;

	private static final ThreadLocal<Object> REGISTRATION_HANDLES = new ThreadLocal<>();

	private static volatile Method pullClient;
	private static volatile Method clientGameThread;
	private static volatile Method scheduleClient;

	private WorkerEnvironment() {
	}

	/**
	 * Resolves the game classloader if it has not been resolved yet.
	 * <p>
	 * A thread whose own context loader can already see the game wins, because that is the loader
	 * the game itself is using. Otherwise the loader that defined {@code Item} is the one Forge
	 * uses to define every mod class.
	 */
	public static void capture() {
		ClassLoader current = Thread.currentThread().getContextClassLoader();
		if (seesGame(current)) {
			remember(current);
			return;
		}
		if (gameLoader == null) {
			remember(resolveGameLoader());
		}
	}

	public static ClassLoader gameClassLoader() {
		ClassLoader loader = gameLoader;
		if (loader == null) {
			capture();
			loader = gameLoader;
		}
		return loader;
	}

	/**
	 * Thread group for a worker. {@link net.minecraftforge.fml.util.thread.EffectiveSide} reads the
	 * group, and a worker left in the submitting thread's group is reported as the client even on a
	 * dedicated server.
	 */
	public static ThreadGroup workerThreadGroup() {
		if (FMLEnvironment.dist.isDedicatedServer()) {
			return SidedThreadGroups.SERVER;
		}
		return SidedThreadGroups.CLIENT;
	}

	/**
	 * Context loader to hand Forge's event transformer when the calling thread does not already
	 * have one that can see the game.
	 */
	public static ClassLoader loaderForEvents(ClassLoader threadLoader) {
		ClassLoader game = gameLoader;
		if (game == null || threadLoader == game || seesGame(threadLoader)) {
			return threadLoader;
		}
		return game;
	}

	/**
	 * Pulls services and client handles on the calling thread, which is the thread that owns them.
	 * Startup calls this on the render thread before the worker begins, so the first
	 * {@code ServiceLoader} lookup and the accessor reads do not happen off-thread.
	 */
	public static void prepareCaller() {
		capture();
		Thread thread = Thread.currentThread();
		ClassLoader previousLoader = thread.getContextClassLoader();
		ClassLoader game = gameClassLoader();
		if (game != null) {
			thread.setContextClassLoader(game);
		}
		try {
			pullNeededState();
		} finally {
			thread.setContextClassLoader(previousLoader);
		}
	}

	/**
	 * Runs {@code task} with the game classloader, after pulling the services and client handles
	 * the task's plugins are about to touch.
	 * <p>
	 * The client snapshot is installed only off the client game thread. On that thread the live
	 * fields are the snapshot, and overlaying them would hide a handle that changed between phases.
	 */
	public static void callWithLoader(Runnable task) {
		capture();
		Thread thread = Thread.currentThread();
		ClassLoader previousLoader = thread.getContextClassLoader();
		ClassLoader game = gameClassLoader();
		if (game != null) {
			thread.setContextClassLoader(game);
		}
		Object previousHandles = REGISTRATION_HANDLES.get();
		boolean overlaid = false;
		try {
			pullNeededState();
			if (clientSnapshot != null && !isClientGameThread()) {
				installClientFields(clientSnapshot);
				REGISTRATION_HANDLES.set(clientSnapshot);
				overlaid = true;
			}
			task.run();
		} finally {
			if (overlaid) {
				restoreHandles(previousHandles);
			}
			thread.setContextClassLoader(previousLoader);
		}
	}

	/**
	 * Installs the already-pulled snapshot around one plugin callback. The caller is responsible
	 * for having run {@link #callWithLoader} first, which is what takes the snapshot.
	 */
	public static void runRegistration(Runnable task) {
		Thread thread = Thread.currentThread();
		ClassLoader previousLoader = thread.getContextClassLoader();
		ClassLoader game = gameClassLoader();
		if (game != null) {
			thread.setContextClassLoader(game);
		}
		Object previousHandles = REGISTRATION_HANDLES.get();
		Object snapshot = clientSnapshot;
		if (snapshot != null) {
			installClientFields(snapshot);
			REGISTRATION_HANDLES.set(snapshot);
		}
		try {
			task.run();
		} finally {
			if (snapshot != null) {
				restoreHandles(previousHandles);
			}
			thread.setContextClassLoader(previousLoader);
		}
	}

	/**
	 * Sets only the classloader. Search and other non-registration work must keep reading live
	 * client state, so it does not install the registration snapshot.
	 */
	public static Runnable wrapLoader(Runnable task) {
		return () -> {
			Thread thread = Thread.currentThread();
			ClassLoader previousLoader = thread.getContextClassLoader();
			ClassLoader game = gameClassLoader();
			if (game != null) {
				thread.setContextClassLoader(game);
			}
			try {
				task.run();
			} finally {
				thread.setContextClassLoader(previousLoader);
			}
		};
	}

	/**
	 * The client handles installed for the current registration callback, or null when the caller
	 * should read Minecraft live.
	 */
	public static Object registrationHandles() {
		return REGISTRATION_HANDLES.get();
	}

	/**
	 * Level captured on the game thread before the worker began. Used as a fallback when a
	 * registration handle is not installed on the current thread.
	 */
	public static Object clientLevelSnapshot() {
		Object snapshot = clientSnapshot;
		if (snapshot == null) {
			return null;
		}
		try {
			return snapshot.getClass().getField("level").get(snapshot);
		} catch (ReflectiveOperationException ignored) {
			return null;
		}
	}

	private static void pullNeededState() {
		pullJeiService();
		pullClientHandles();
	}

	/**
	 * Forces JEI's own {@code ServiceLoader} platform helper to initialise on this thread, which
	 * is already wearing the game classloader. A later worker then reads the static instead of
	 * being the thread that first resolves the service.
	 */
	private static void pullJeiService() {
		if (jeiServicePulled) {
			return;
		}
		try {
			Class.forName("mezz.jei.common.platform.Services").getField("PLATFORM").get(null);
			jeiServicePulled = true;
		} catch (Throwable t) {
			jeiServicePulled = true;
			LOGGER.warn("jeasync: could not load JEI's platform service on the game classloader", t);
		}
	}

	private static void pullClientHandles() {
		if (FMLEnvironment.dist.isDedicatedServer()) {
			return;
		}
		try {
			Method pull = pullClient;
			if (pull == null) {
				pull = Class.forName("dev.jeasync.client.ClientStatePuller").getDeclaredMethod("pull");
				pullClient = pull;
			}
			Object pulled = pull.invoke(null);
			if (pulled != null) {
				clientSnapshot = pulled;
			}
		} catch (Throwable t) {
			LOGGER.warn("jeasync: could not pull client state for worker threads", t);
		}
	}

	public static boolean isClientGameThread() {
		if (FMLEnvironment.dist.isDedicatedServer()) {
			return false;
		}
		try {
			Method check = clientGameThread;
			if (check == null) {
				check = Class.forName("dev.jeasync.client.ClientStatePuller").getDeclaredMethod("isClientGameThread");
				clientGameThread = check;
			}
			return Boolean.TRUE.equals(check.invoke(null));
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Runs {@code task} on the client thread and waits until it finishes.
	 * <p>
	 * A call that is already on the client thread runs inline. A dedicated server has no client
	 * thread, so it runs inline there too. The wait is safe while background startup owns the
	 * caller and the client thread is free to drain its queue.
	 */
	public static void runOnClient(Runnable task) {
		if (FMLEnvironment.dist.isDedicatedServer() || isClientGameThread()) {
			task.run();
			return;
		}
		CompletableFuture<Void> done = new CompletableFuture<>();
		Runnable wrapped = () -> {
			try {
				task.run();
				done.complete(null);
			} catch (Throwable t) {
				done.completeExceptionally(t);
			}
		};
		try {
			Method schedule = scheduleClient;
			if (schedule == null) {
				schedule = Class.forName("dev.jeasync.client.ClientStatePuller")
					.getDeclaredMethod("schedule", Runnable.class);
				scheduleClient = schedule;
			}
			schedule.invoke(null, wrapped);
		} catch (Throwable t) {
			LOGGER.warn("jeasync: could not move work onto the client thread", t);
			if (!done.isDone()) {
				task.run();
			}
			return;
		}
		done.join();
	}

	/**
	 * Publishes the client handles pulled on the game thread into Minecraft's public fields, so code
	 * that reads those fields directly (rather than through a getter) sees them on a worker thread.
	 */
	private static void installClientFields(Object snapshot) {
		if (FMLEnvironment.dist.isDedicatedServer() || snapshot == null) {
			return;
		}
		try {
			Class<?> puller = Class.forName("dev.jeasync.client.ClientStatePuller");
			puller.getMethod("installFields", Object.class).invoke(null, snapshot);
		} catch (Throwable ignored) {
			// Leave the live fields untouched if the client class is unavailable.
		}
	}

	private static void restoreHandles(Object previous) {
		if (previous == null) {
			REGISTRATION_HANDLES.remove();
		} else {
			REGISTRATION_HANDLES.set(previous);
		}
	}

	private static void remember(ClassLoader loader) {
		if (loader == null) {
			return;
		}
		gameLoader = loader;
		if (!announcedLoader) {
			announcedLoader = true;
			LOGGER.info("jeasync workers will load classes and services through {}", loader.getClass().getName());
		}
	}

	private static ClassLoader resolveGameLoader() {
		try {
			ClassLoader defined = Class.forName("net.minecraft.world.item.Item").getClassLoader();
			if (defined != null) {
				return defined;
			}
		} catch (ClassNotFoundException ignored) {
			// Resolved again on the next capture. Early mixin application can beat Minecraft.
		}
		ClassLoader current = Thread.currentThread().getContextClassLoader();
		if (current != null) {
			return current;
		}
		return WorkerEnvironment.class.getClassLoader();
	}

	private static boolean seesGame(ClassLoader loader) {
		if (loader == null) {
			return false;
		}
		String name = loader.getClass().getName();
		return name.equals("cpw.mods.modlauncher.TransformingClassLoader")
			|| name.equals("cpw.mods.cl.ModuleClassLoader");
	}
}
