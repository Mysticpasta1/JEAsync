package dev.jeiasync;

import net.neoforged.fml.loading.FMLEnvironment;

import java.lang.reflect.Method;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Progress for a JEI startup that is running off the render thread.
 * <p>
 * The render thread reads this while a container screen is open. Plugin phases report into it from
 * the startup thread. The fraction stays under 1 until {@link #succeed()} so the bar does not claim
 * the load is finished during the last non-plugin bit of work.
 * <p>
 * GUI event registration is held until startup has published a runtime, then run on the client
 * thread. Registering those listeners from a worker while a screen is rendering races Forge's
 * event bus.
 */
public final class JeiLoadProgress {
	/**
	 * {@code callOnPlugins} phases inside {@code JeiStarter.start}, not counting the config hand-off
	 * that already ran when the client was constructed.
	 */
	private static final int EXPECTED_STAGES = 17;

	private static final AtomicInteger stagesStarted = new AtomicInteger();
	private static final AtomicInteger stageDone = new AtomicInteger();
	private static final AtomicInteger stageTotal = new AtomicInteger(1);

	private static volatile boolean loading;
	private static volatile boolean failed;
	private static volatile boolean guiReady;
	private static volatile boolean listeningDuringLoad;
	private static volatile boolean restartAfterLoad;
	private static volatile boolean stopAfterLoad;
	private static volatile String stage = "Starting JEI";
	private static volatile String failure = "JEI failed to load";
	private static volatile Thread worker;
	private static volatile Runnable deferredGui;
	private static volatile Method scheduleClient;

	private JeiLoadProgress() {
	}

	public static boolean isLoading() {
		return loading;
	}

	/** True while the bar should stay on screen, including after a failed load. */
	public static boolean isVisible() {
		return loading || failed || deferredGui != null;
	}

	/** True once JEI's screen listeners have been registered on the client thread. */
	public static boolean isGuiReady() {
		return guiReady;
	}

	public static boolean isStartupThread() {
		return Thread.currentThread() == worker;
	}

	public static void begin(Runnable work) {
		loading = true;
		failed = false;
		guiReady = false;
		listeningDuringLoad = false;
		restartAfterLoad = false;
		stopAfterLoad = false;
		deferredGui = null;
		stage = "Starting JEI";
		stagesStarted.set(0);
		stageDone.set(0);
		stageTotal.set(1);
		CompletableFuture.runAsync(() -> {
			worker = Thread.currentThread();
			try {
				work.run();
			} finally {
				worker = null;
			}
		}, AsyncPluginSupport.getStartupExecutor());
	}

	/**
	 * A transition back to listening arrived while startup was still running. The render thread
	 * must not wait for that startup: a worker may be queued on the client thread.
	 */
	public static void noteListeningDuringLoad() {
		listeningDuringLoad = true;
		stopAfterLoad = true;
	}

	/**
	 * A start arrived after {@link #noteListeningDuringLoad()} in the same transition. That pair
	 * is a restart, not a disconnect.
	 */
	public static void noteStartDuringLoad() {
		if (listeningDuringLoad) {
			listeningDuringLoad = false;
			stopAfterLoad = false;
			restartAfterLoad = true;
		}
	}

	public static boolean consumeRestart() {
		boolean value = restartAfterLoad;
		restartAfterLoad = false;
		return value;
	}

	public static boolean consumeStop() {
		boolean value = stopAfterLoad;
		stopAfterLoad = false;
		listeningDuringLoad = false;
		return value;
	}

	/** Holds JEI's GUI registration until {@link #publishDeferredGui()} runs on the client thread. */
	public static void deferGuiRegistration(Runnable register) {
		deferredGui = register;
	}

	public static void dropDeferredGui() {
		deferredGui = null;
		guiReady = false;
	}

	public static void publishDeferredGui() {
		Runnable register = deferredGui;
		deferredGui = null;
		if (register == null) {
			guiReady = !failed;
			return;
		}
		guiReady = true;
		try {
			register.run();
		} catch (Throwable t) {
			guiReady = false;
			org.apache.logging.log4j.LogManager.getLogger(JeiLoadProgress.class)
				.error("JEI Async: could not attach the GUI", t);
			fail("JEI failed to open");
		}
	}

	public static void succeed() {
		loading = false;
		failed = false;
		scheduleOnClient(JeiLoadProgress::publishDeferredGui);
	}

	/** Startup was abandoned. Hides the bar and does not attach the GUI. */
	public static void finish() {
		loading = false;
		failed = false;
		guiReady = false;
		deferredGui = null;
	}

	public static void fail(String message) {
		failure = message;
		failed = true;
		loading = false;
		deferredGui = null;
		guiReady = false;
	}

	public static void beginStage(String title, int plugins) {
		if (!loading) {
			return;
		}
		stage = title;
		stagesStarted.incrementAndGet();
		stageDone.set(0);
		stageTotal.set(Math.max(plugins, 1));
	}

	public static void pluginFinished() {
		if (!loading) {
			return;
		}
		stageDone.incrementAndGet();
	}

	public static float fraction() {
		if (!loading) {
			return 1f;
		}
		int started = stagesStarted.get();
		int finishedStages = Math.max(0, started - 1);
		float within = stageDone.get() / (float) Math.max(stageTotal.get(), 1);
		if (within > 1f) {
			within = 1f;
		}
		float value = (finishedStages + within) / EXPECTED_STAGES;
		if (value < 0f) {
			return 0f;
		}
		return Math.min(value, 0.99f);
	}

	public static String label() {
		if (failed) {
			return failure;
		}
		if (!loading && deferredGui != null) {
			return "Opening JEI";
		}
		int done = stageDone.get();
		int total = stageTotal.get();
		if (loading && total > 1) {
			return stage + " (" + Math.min(done, total) + "/" + total + ")";
		}
		return stage;
	}

	private static void scheduleOnClient(Runnable task) {
		if (FMLEnvironment.dist.isDedicatedServer()) {
			return;
		}
		try {
			Method schedule = scheduleClient;
			if (schedule == null) {
				schedule = Class.forName("dev.jeiasync.client.ClientStatePuller")
					.getDeclaredMethod("schedule", Runnable.class);
				scheduleClient = schedule;
			}
			schedule.invoke(null, task);
		} catch (Throwable t) {
			org.apache.logging.log4j.LogManager.getLogger(JeiLoadProgress.class)
				.error("JEI Async: could not schedule GUI registration", t);
			fail("JEI failed to open");
		}
	}

	public static boolean failed() {
		return failed;
	}
}
