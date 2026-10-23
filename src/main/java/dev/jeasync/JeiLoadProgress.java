package dev.jeasync;

import net.minecraftforge.fml.loading.FMLEnvironment;

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
	/**
	 * True while the post-startup search build is running. Heavy indexes stay unbuilt for the
	 * rest of {@code loading}, then this flag lets that one build proceed.
	 */
	private static volatile boolean buildingDeferredSearch;
	private static volatile Runnable deferredHeavySearch;
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
		buildingDeferredSearch = false;
		deferredHeavySearch = null;
		listeningDuringLoad = false;
		restartAfterLoad = false;
		stopAfterLoad = false;
		deferredGui = null;
		stage = "Starting JEI";
		ItemSubtypeCache.clear();
		TypedIngredientIntern.clear();
		AnvilEnchantCache.clear();
		ArcaneRecipeTags.clear();
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

	/**
	 * A recipe sync arrived while this startup is already running.
	 * Mysticpasta1 — JEI can ignore that event, and the in-progress pass would keep the old list.
	 * A rejoin's logout flag must not drop this. The pass keeps the sync and applies it if the player is back.
	 */
	public static void requestRestart() {
		if (!loading) {
			return;
		}
		// Mysticpasta1 — a rejoin sets the stop flag before the new world's packet finishes.
		// The sync still has to be recorded so that packet can replace the list once the player is back.
		restartAfterLoad = true;
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
				.error("jeasync: could not attach the GUI", t);
			fail("JEI failed to open");
		}
	}

	/**
	 * True while plugin startup is still running and the heavy search indexes should not be built.
	 * Name search is built immediately. Tooltips, tags, and the other prefixes wait until
	 * {@link #succeed()} so their allocations do not stack on recipe registration.
	 */
	public static boolean isDeferringHeavySearch() {
		return loading && !buildingDeferredSearch;
	}

	/** Replaces any earlier callback. Only the latest filter is upgraded. */
	public static void deferHeavySearch(Runnable task) {
		deferredHeavySearch = task;
	}

	/** Builds the deferred tooltip and tag indexes while startup is still marked loading. */
	public static void buildDeferredSearch() {
		runDeferredHeavySearch();
	}

	/**
	 * Startup is finished. A recipe sync that arrives after this takes the normal restart path
	 * instead of the in-load flag.
	 */
	public static void publish() {
		loading = false;
		failed = false;
		TypedIngredientIntern.clear();
		AnvilEnchantCache.clear();
		scheduleOnClient(JeiLoadProgress::publishDeferredGui);
	}

	public static void succeed() {
		buildDeferredSearch();
		publish();
	}

	/** Startup was abandoned. Hides the bar and does not attach the GUI. */
	public static void finish() {
		loading = false;
		failed = false;
		guiReady = false;
		deferredGui = null;
		deferredHeavySearch = null;
		TypedIngredientIntern.clear();
		AnvilEnchantCache.clear();
	}

	public static void fail(String message) {
		failure = message;
		failed = true;
		loading = false;
		deferredGui = null;
		deferredHeavySearch = null;
		guiReady = false;
		TypedIngredientIntern.clear();
		AnvilEnchantCache.clear();
	}

	private static void runDeferredHeavySearch() {
		Runnable task = deferredHeavySearch;
		deferredHeavySearch = null;
		if (task == null) {
			return;
		}
		stage = "Building search";
		buildingDeferredSearch = true;
		try {
			task.run();
		} catch (Throwable t) {
			org.apache.logging.log4j.LogManager.getLogger(JeiLoadProgress.class)
				.error("jeasync: deferred search index failed", t);
		} finally {
			buildingDeferredSearch = false;
		}
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
				schedule = Class.forName("dev.jeasync.client.ClientStatePuller")
					.getDeclaredMethod("schedule", Runnable.class);
				scheduleClient = schedule;
			}
			schedule.invoke(null, task);
		} catch (Throwable t) {
			org.apache.logging.log4j.LogManager.getLogger(JeiLoadProgress.class)
				.error("jeasync: could not schedule GUI registration", t);
			fail("JEI failed to open");
		}
	}

	public static boolean failed() {
		return failed;
	}
}
