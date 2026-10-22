package dev.jeiasync.mixin.startup;

import dev.jeiasync.ClientRecipeViews;
import dev.jeiasync.JeiLoadProgress;
import dev.jeiasync.MixinApplied;
import dev.jeiasync.WorkerEnvironment;
import dev.jeiasync.client.JeiLoadOverlay;
import dev.jeiasync.client.RecipeSyncProgress;
import mezz.jei.common.Internal;
import mezz.jei.common.network.IConnectionToServer;
import mezz.jei.forge.events.PermanentEventSubscriptions;
import mezz.jei.forge.startup.StartEventObserver;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.item.crafting.Recipe;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.ConcurrentModificationException;

/**
 * Runs JEI's startup off the render thread.
 * <p>
 * {@code transitionState} is the single place startup and shutdown go through, including the
 * container-screen path that force-starts JEI when a screen opens early. The render thread marks
 * JEI started and returns. The worker runs {@code startRunnable}. A second start while that worker
 * is busy is recorded and applied when the current pass finishes. Waiting for it on the render
 * thread deadlocks a plugin that queued client work, which is how an early inventory open used to
 * hang or crash.
 * <p>
 * Startup used to wait for {@code RecipesUpdatedEvent} whenever the server might send recipes.
 * A connection change clears the login flag, and the recipe handler then records the sync and
 * returns without starting. JEI stayed listening until a container screen force-started it.
 * The client tick now takes that same screen path once the local player is in a level, holding
 * the start until JEI has a recipe record for this connection so the plugins read the server
 * sync instead of a vanilla fallback; the hold expires so a missing packet cannot leave JEI dark.
 * A filled manager is still kept, so Avaritia does not lose recipes that have already arrived.
 * A sync that lands while startup is running is applied when the current pass finishes.
 * <p>
 * A disconnect during startup is applied after the worker finishes, unless the player is already
 * back in a world. A rejoin fires logout while startup is still running, and cancelling that
 * transition leaves the state at {@code JEI_STARTED}, so the new login cannot start JEI.
 * Tearing the finished runtime down then made the client tick begin another full load.
 * A recipe sync that arrives during startup, including while the deferred search indexes are
 * built, stops and starts again on that same worker. A few syncs in one load are applied.
 * Further ones are logged and skipped so a plugin that posts recipe events cannot restart forever.
 * <p>
 * Targets upstream 15.74.0.225.
 */
@Mixin(value = StartEventObserver.class, remap = false)
public abstract class StartEventObserverMixin {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final int MAX_RESTARTS = 4;
	/**
	 * How far past the last recipe-packet decode activity the tick-start may wait for the sync
	 * record before starting on the client fallback. A decode stamps its progress by recipe, so
	 * patience is anchored at the freshest activity: a packet that reads for a minute keeps the
	 * hold alive, and the wait only expires when everything is quiet with no sync record.
	 */
	private static final long SYNC_PATIENCE_MS = 90_000;

	@Shadow
	private IConnectionToServer serverConnection;

	@Shadow
	private boolean observedLogin;

	@Shadow
	private boolean observedRecipeSync;

	@Shadow
	private void startIfReady() {
		throw new AssertionError();
	}

	/**
	 * The player join that already took the screen's force-start path. Logged-out clients clear this
	 * so the next world can start again.
	 */
	@Unique
	private boolean jeiasync$forced;

	@Unique
	private boolean jeiasync$loadingAtRecipeHead;

	@Unique
	private static Field jeiasync$stateField;

	@Unique
	private static long jeiasync$syncWaitStart;

	@Unique
	private static Method jeiasync$transitionMethod;

	@Unique
	private static Class<?> jeiasync$stateType;

	// Reverted: let JEI wait for RecipesUpdatedEvent when the server may send recipes.

	/**
	 * @author Mysticpasta1
	 * @reason JEI's own tick registration can miss the join. This mod's client tick is the one that runs.
	 */
	@Inject(method = "register", at = @At("TAIL"), remap = false)
	private void jeiasync$watchForLoadedRecipes(PermanentEventSubscriptions subscriptions, CallbackInfo ci) {
		JeiLoadOverlay.armStartup(this::jeiasync$startFromClientTick);
	}

	/**
	 * @author Mysticpasta1
	 * @reason {@code startIfReady} returns immediately when login was never observed.
	 */
	@Inject(method = "startIfReady", at = @At("HEAD"), remap = false)
	private void jeiasync$acceptLoadedRecipes(CallbackInfo ci) {
		jeiasync$markLoadedWorldReady();
	}

	/**
	 * @author Mysticpasta1
	 * @reason Remember whether this event arrived in the middle of a startup that was already running.
	 */
	@Inject(method = "onRecipesUpdatedEvent", at = @At("HEAD"), remap = false)
	private void jeiasync$noteLoadOnRecipe(CallbackInfo ci) {
		this.jeiasync$loadingAtRecipeHead = JeiLoadProgress.isLoading();
	}

	/**
	 * @author Mysticpasta1
	 * @reason An ignored recipe event never reaches {@code startIfReady}, so the inventory screen was the only start.
	 */
	@Inject(method = "onRecipesUpdatedEvent", at = @At("RETURN"), remap = false)
	private void jeiasync$startAfterIgnoredSync(CallbackInfo ci) {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (minecraft.player == null || level == null) {
			return;
		}
		boolean stale = level.getRecipeManager() instanceof ClientRecipeViews views && views.jeiasync$servedStale();
		if (!jeiasync$managerHasRecipes()) {
			return;
		}
		boolean startedDuringEvent = !this.jeiasync$loadingAtRecipeHead && JeiLoadProgress.isLoading();
		if (startedDuringEvent) {
			return;
		}
		if (JeiLoadProgress.isLoading()) {
			if (stale) {
				LOGGER.info("JEI Async: server recipes replaced a list plugins already read, restarting registration");
				JeiLoadProgress.requestRestart();
			} else {
				LOGGER.info("JEI Async: server recipes are in the client manager before plugins read them");
			}
			return;
		}
		if (jeiasync$isStarted()) {
			// Startup already published, and this event did not restart it. The manager now has the new list.
			jeiasync$forceStart("recipe sync");
			return;
		}
		jeiasync$markLoadedWorldReady();
		jeiasync$forceStart("recipe sync");
	}

	/**
	 * @author Mysticpasta1
	 * @reason Take the container-screen start path as soon as the player is in a level.
	 */
	@Unique
	private void jeiasync$startFromClientTick() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			this.jeiasync$forced = false;
			jeiasync$resetSyncWait();
			return;
		}
		if (this.jeiasync$forced || JeiLoadProgress.isLoading()) {
			return;
		}
		if (jeiasync$isStarted()) {
			this.jeiasync$forced = true;
			return;
		}
		if (jeiasync$waitForSync()) {
			return;
		}
		jeiasync$markLoadedWorldReady();
		jeiasync$forceStart("client tick");
	}

	/**
	 * @author Mysticpasta1
	 * @reason Holding the tick-start until JEI has a recipe record for this connection means the
	 * plugins read the server sync from the first pass, and neither the fallback nor JEI's
	 * "server does not provide recipes" warning is ever produced. Patience is anchored at the
	 * last decode activity: a packet that reads for a minute stays held, and the wait only
	 * expires when nothing has decoded recently and no record exists.
	 */
	@Unique
	private static boolean jeiasync$waitForSync() {
		if (Internal.hasClientRecipes()) {
			jeiasync$resetSyncWait();
			return false;
		}
		long now = System.currentTimeMillis();
		if (jeiasync$syncWaitStart == 0) {
			jeiasync$syncWaitStart = now;
			LOGGER.info("JEI Async: holding JEI startup until the server recipe sync arrives");
			return true;
		}
		long anchor = Math.max(jeiasync$syncWaitStart, RecipeSyncProgress.lastActivityMs());
		if (now - anchor > SYNC_PATIENCE_MS) {
			LOGGER.warn("JEI Async: no server recipe sync within {} ms of the last packet activity, starting JEI with the client fallback",
				SYNC_PATIENCE_MS);
			jeiasync$resetSyncWait();
			return false;
		}
		return true;
	}

	@Unique
	private static void jeiasync$resetSyncWait() {
		jeiasync$syncWaitStart = 0;
	}

	/** Non-free heap in whole MB, for the load log lines that compare passes and restarts. */
	@Unique
	private static long jeiasync$usedHeapMb() {
		Runtime runtime = Runtime.getRuntime();
		return Math.max(0, (runtime.totalMemory() - runtime.freeMemory()) >> 20);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Login stays false after a missed connection event. A filled manager is enough for {@code startIfReady}.
	 */
	@Unique
	private boolean jeiasync$markLoadedWorldReady() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null || !jeiasync$managerHasRecipes()) {
			return false;
		}
		this.observedLogin = true;
		this.observedRecipeSync = true;
		return true;
	}

	/**
	 * @author Mysticpasta1
	 * @reason Call the same two transitions the inventory screen uses, which do not check the login flag.
	 */
	@Unique
	private void jeiasync$forceStart(String source) {
		this.jeiasync$forced = true;
		try {
			LOGGER.info("JEI Async: starting from the {} without a container screen, heap {} MB used of {} MB max",
				source, jeiasync$usedHeapMb(), Math.max(0, Runtime.getRuntime().maxMemory() >> 20));
			jeiasync$invokeTransition("LISTENING");
			jeiasync$invokeTransition("JEI_STARTED");
			if (!JeiLoadProgress.isLoading() && !jeiasync$isStarted()) {
				this.jeiasync$forced = false;
			}
		} catch (ReflectiveOperationException e) {
			this.jeiasync$forced = false;
			LOGGER.error("JEI Async: could not start from the {}", source, e);
		}
	}

	@Unique
	private boolean jeiasync$isStarted() {
		try {
			return "JEI_STARTED".equals(String.valueOf(jeiasync$stateField().get(this)));
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	@Unique
	private static Field jeiasync$stateField() throws NoSuchFieldException {
		Field field = jeiasync$stateField;
		if (field == null) {
			field = StartEventObserver.class.getDeclaredField("state");
			field.setAccessible(true);
			jeiasync$stateField = field;
		}
		return field;
	}

	@Unique
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void jeiasync$invokeTransition(String name) throws ReflectiveOperationException {
		Class<?> type = jeiasync$stateType;
		Method method = jeiasync$transitionMethod;
		if (method == null || type == null) {
			type = Class.forName("mezz.jei.forge.startup.StartEventObserver$State");
			method = StartEventObserver.class.getDeclaredMethod("transitionState", type);
			method.setAccessible(true);
			jeiasync$stateType = type;
			jeiasync$transitionMethod = method;
		}
		method.invoke(this, Enum.valueOf((Class) type, name));
	}

	private static boolean jeiasync$managerHasRecipes() {
		Minecraft minecraft = Minecraft.getInstance();
		ClientLevel level = minecraft.level;
		if (level == null) {
			return false;
		}
		try {
			Collection<Recipe<?>> recipes = level.getRecipeManager().getRecipes();
			return recipes != null && !recipes.isEmpty();
		} catch (ConcurrentModificationException ignored) {
			return true;
		}
	}

	@Inject(method = "transitionState", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeiasync$transition(@Coerce Object newState, CallbackInfo ci) {
		MixinApplied.mark("startup.StartEventObserverMixin");
		String name = String.valueOf(newState);

		if ("LISTENING".equals(name)) {
			if (JeiLoadProgress.isLoading()) {
				if (!JeiLoadProgress.isStartupThread()) {
					JeiLoadProgress.noteListeningDuringLoad();
				}
				ci.cancel();
			}
			return;
		}

		if (!"JEI_STARTED".equals(name)) {
			return;
		}
		if (JeiLoadProgress.isLoading()) {
			JeiLoadProgress.noteStartDuringLoad();
			ci.cancel();
			return;
		}

		// Cancel before any of the hand-off can throw. Falling through runs start() on the render
		// thread and races the worker, which throws "already been registered" from the open screen.
		ci.cancel();
		try {
			WorkerEnvironment.prepareCaller();
			assignState(newState);
			clearObserved();
			JeiLoadProgress.begin(() -> WorkerEnvironment.callWithLoader(this::runStart));
		} catch (Exception e) {
			LOGGER.error("JEI Async: could not move startup off the render thread", e);
			JeiLoadProgress.fail("JEI failed to load");
		}
	}

	private void runStart() {
		try {
			int restarts = 0;
			invokeStart();
			while (true) {
				if (abandonIfStopped()) {
					return;
				}
				if (restarts < MAX_RESTARTS && JeiLoadProgress.consumeRestart()) {
					restarts++;
					LOGGER.info("JEI Async: recipe sync arrived during startup, restarting ({}/{})", restarts, MAX_RESTARTS);
					invokeStop();
					invokeStart();
					continue;
				}
				if (!Internal.getOptionalJeiRuntime().isPresent()) {
					LOGGER.error("JEI Async: startup finished without a runtime");
					JeiLoadProgress.dropDeferredGui();
					JeiLoadProgress.fail("JEI failed to load");
					return;
				}
				JeiLoadProgress.buildDeferredSearch();
				if (abandonIfStopped()) {
					return;
				}
				boolean restart = JeiLoadProgress.consumeRestart();
				if (restart && restarts < MAX_RESTARTS) {
					restarts++;
					LOGGER.info("JEI Async: recipe sync arrived while search indexes were building, restarting ({}/{})", restarts, MAX_RESTARTS);
					invokeStop();
					invokeStart();
					continue;
				}
				if (restart) {
					LOGGER.warn("JEI Async: a recipe sync arrived after {} restarts and was not applied", MAX_RESTARTS);
				}
				JeiLoadProgress.publish();
				LOGGER.info("JEI Async: JEI loaded with heap {} MB used of {} MB allocated", jeiasync$usedHeapMb(),
					Runtime.getRuntime().totalMemory() >> 20);
				return;
			}
		} catch (Throwable t) {
			LOGGER.error("JEI Async: startup failed", t);
			JeiLoadProgress.dropDeferredGui();
			JeiLoadProgress.fail("JEI failed to load");
		}
	}

	private boolean abandonIfStopped() throws ReflectiveOperationException {
		if (!JeiLoadProgress.consumeStop()) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		// Mysticpasta1 — logout during the load is a rejoin when the player is in a world again at the end.
		if (minecraft.player != null && minecraft.level != null) {
			LOGGER.info("JEI Async: the player rejoined during startup, so this pass is kept");
			return false;
		}
		JeiLoadProgress.dropDeferredGui();
		assignListening();
		if (Internal.getOptionalJeiRuntime().isPresent()) {
			invokeStop();
		}
		JeiLoadProgress.finish();
		return true;
	}

	private void assignState(Object newState) throws ReflectiveOperationException {
		Field field = StartEventObserver.class.getDeclaredField("state");
		field.setAccessible(true);
		field.set(this, newState);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void assignListening() throws ReflectiveOperationException {
		Class type = Class.forName("mezz.jei.forge.startup.StartEventObserver$State");
		assignState(Enum.valueOf(type, "LISTENING"));
	}

	private void clearObserved() throws ReflectiveOperationException {
		Method method = StartEventObserver.class.getDeclaredMethod("clearObservedStartEvents");
		method.setAccessible(true);
		method.invoke(this);
	}

	private void invokeStart() throws ReflectiveOperationException {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.level != null && minecraft.level.getRecipeManager() instanceof ClientRecipeViews views) {
			views.jeiasync$clearServed();
		}
		Field field = StartEventObserver.class.getDeclaredField("startRunnable");
		field.setAccessible(true);
		((Runnable) field.get(this)).run();
	}

	private void invokeStop() throws ReflectiveOperationException {
		Field field = StartEventObserver.class.getDeclaredField("stopRunnable");
		field.setAccessible(true);
		((Runnable) field.get(this)).run();
	}
}
