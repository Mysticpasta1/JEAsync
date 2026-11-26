package dev.jeasync.mixin.startup;

import dev.jeasync.JeiLoadProgress;
import dev.jeasync.MixinApplied;
import dev.jeasync.WorkerEnvironment;
import dev.jeasync.compat.RecoletaHooks;
import dev.jeasync.compat.RecipeViewerCatalogCache;
import dev.jeasync.compat.XyCraftCaches;
import dev.jeasync.client.JeiLoadOverlay;
import mezz.jei.common.Internal;
import mezz.jei.neoforge.startup.StartEventObserver;
import net.minecraft.client.Minecraft;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runs JEI's startup off the render thread.
 * <p>
 * {@code transitionState} is the single place startup and shutdown go through. The render thread
 * marks JEI started and returns; the worker runs {@code startRunnable}. A second start while that
 * worker is busy is recorded and applied when the current pass finishes, which is how JEI 19's own
 * recipe-sync restart is handled without waiting on the render thread.
 * <p>
 * A disconnect during startup is applied after the worker finishes, unless the player is already
 * back in a world. Tearing the runtime down on the render thread while a worker is still
 * registering is what used to leave a half-built runtime behind.
 * <p>
 * JEI 19 already starts on both the login and the recipe-sync event. The client-tick fallback is
 * kept for the case where those events were missed; it only ever calls the same start path JEI
 * uses when a screen opens early.
 */
@Mixin(value = StartEventObserver.class, remap = false)
public abstract class StartEventObserverMixin {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final int MAX_RESTARTS = 4;
	private static final long SYNC_PATIENCE_MS = 90_000;

	@Shadow
	private boolean observedLogin;

	@Shadow
	private boolean observedRecipeSync;

	@Shadow
	private void startIfReady() {
		throw new AssertionError();
	}

	@Unique
	private boolean jeasync$forced;

	@Unique
	private static long jeasync$syncWaitStart;

	@Unique
	private static Field jeasync$stateField;

	@Unique
	private static Field jeasync$startRunnable;

	@Unique
	private static Field jeasync$stopRunnable;

	@Unique
	private static Method jeasync$clearObserved;

	@Unique
	private static Method jeasync$transitionMethod;

	@Unique
	private static Class<?> jeasync$stateType;

	@Inject(method = "register", at = @At("HEAD"), remap = false)
	private void jeasync$armTickFallback(CallbackInfo ci) {
		JeiLoadOverlay.armStartup(this::jeasync$clientTick);
	}

	/**
	 * @author Mysticpasta1
	 * @reason Hand startup to the worker instead of running it on the render thread.
	 */
	@Inject(method = "transitionState", at = @At("HEAD"), cancellable = true, remap = false)
	private void jeasync$transition(@Coerce Object newState, CallbackInfo ci) {
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

		ci.cancel();
		try {
			WorkerEnvironment.prepareCaller();
			jeasync$assignState(newState);
			jeasync$clearObserved();
			JeiLoadProgress.begin(() -> WorkerEnvironment.callWithLoader(this::jeasync$runStart));
		} catch (Exception e) {
			LOGGER.error("jeasync: could not move startup off the render thread", e);
			JeiLoadProgress.fail("JEI failed to load");
		}
	}

	/**
	 * Mysticpasta1 — the client tick has to reach JEI even when JEI's own bus misses the join.
	 * It only calls the same {@code startIfReady} the screen path uses.
	 */
	@Unique
	private void jeasync$clientTick() {
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player == null || minecraft.level == null) {
			this.jeasync$forced = false;
			jeasync$syncWaitStart = 0L;
			return;
		}
		if (this.jeasync$forced || JeiLoadProgress.isLoading()) {
			return;
		}
		if (jeasync$isStarted()) {
			this.jeasync$forced = true;
			return;
		}
		if (!Internal.hasClientSyncedRecipes()) {
			long now = System.currentTimeMillis();
			if (jeasync$syncWaitStart == 0L) {
				jeasync$syncWaitStart = now;
				LOGGER.info("jeasync: holding JEI startup until the server recipe sync arrives");
				return;
			}
			if (now - jeasync$syncWaitStart <= SYNC_PATIENCE_MS) {
				return;
			}
			LOGGER.warn("jeasync: no server recipe sync within {} ms, starting JEI with the client fallback",
				SYNC_PATIENCE_MS);
			jeasync$syncWaitStart = 0L;
		} else {
			jeasync$syncWaitStart = 0L;
		}
		this.jeasync$forced = true;
		this.observedLogin = true;
		this.observedRecipeSync = true;
		this.startIfReady();
		if (!JeiLoadProgress.isLoading() && !jeasync$isStarted()) {
			this.jeasync$forced = false;
		}
	}

	@Unique
	private void jeasync$runStart() {
		try {
			int restarts = 0;
			jeasync$invokeStart();
			while (true) {
				if (jeasync$abandonIfStopped()) {
					return;
				}
				if (restarts < MAX_RESTARTS && JeiLoadProgress.consumeRestart()) {
					restarts++;
					LOGGER.info("jeasync: recipe sync arrived during startup, restarting ({}/{})", restarts, MAX_RESTARTS);
					jeasync$invokeStop();
					jeasync$invokeStart();
					continue;
				}
				if (!Internal.getOptionalJeiRuntime().isPresent()) {
					LOGGER.error("jeasync: startup finished without a runtime");
					JeiLoadProgress.dropDeferredGui();
					JeiLoadProgress.fail("JEI failed to load");
					return;
				}
				JeiLoadProgress.buildDeferredSearch();
				if (jeasync$abandonIfStopped()) {
					return;
				}
				boolean restart = JeiLoadProgress.consumeRestart();
				if (restart && restarts < MAX_RESTARTS) {
					restarts++;
					LOGGER.info("jeasync: recipe sync arrived while search indexes were building, restarting ({}/{})",
						restarts, MAX_RESTARTS);
					jeasync$invokeStop();
					jeasync$invokeStart();
					continue;
				}
				if (restart) {
					LOGGER.warn("jeasync: a recipe sync arrived after {} restarts and was not applied", MAX_RESTARTS);
				}
				RecipeViewerCatalogCache.releaseAfterLoad();
				XyCraftCaches.release();
				JeiLoadProgress.publish();
				RecoletaHooks.afterJeiLoad();
				return;
			}
		} catch (Throwable t) {
			LOGGER.error("jeasync: startup failed", t);
			JeiLoadProgress.dropDeferredGui();
			JeiLoadProgress.fail("JEI failed to load");
		}
	}

	@Unique
	private boolean jeasync$abandonIfStopped() throws ReflectiveOperationException {
		if (!JeiLoadProgress.consumeStop()) {
			return false;
		}
		Minecraft minecraft = Minecraft.getInstance();
		if (minecraft.player != null && minecraft.level != null) {
			LOGGER.info("jeasync: the player rejoined during startup, so this pass is kept");
			return false;
		}
		JeiLoadProgress.dropDeferredGui();
		jeasync$assignListening();
		if (Internal.getOptionalJeiRuntime().isPresent()) {
			jeasync$invokeStop();
		}
		JeiLoadProgress.finish();
		return true;
	}

	@Unique
	private boolean jeasync$isStarted() {
		try {
			return "JEI_STARTED".equals(String.valueOf(jeasync$stateField().get(this)));
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	@Unique
	private static Field jeasync$stateField() throws NoSuchFieldException {
		Field field = jeasync$stateField;
		if (field == null) {
			field = StartEventObserver.class.getDeclaredField("state");
			field.setAccessible(true);
			jeasync$stateField = field;
		}
		return field;
	}

	@Unique
	private void jeasync$assignState(Object newState) throws ReflectiveOperationException {
		jeasync$stateField().set(this, newState);
	}

	@Unique
	@SuppressWarnings({"unchecked", "rawtypes"})
	private void jeasync$assignListening() throws ReflectiveOperationException {
		Class<?> type = jeasync$stateType();
		jeasync$assignState(Enum.valueOf((Class) type, "LISTENING"));
	}

	@Unique
	private static Class<?> jeasync$stateType() throws ClassNotFoundException {
		Class<?> type = jeasync$stateType;
		if (type == null) {
			type = Class.forName("mezz.jei.neoforge.startup.StartEventObserver$State");
			jeasync$stateType = type;
		}
		return type;
	}

	@Unique
	private void jeasync$clearObserved() throws ReflectiveOperationException {
		Method method = jeasync$clearObserved;
		if (method == null) {
			method = StartEventObserver.class.getDeclaredMethod("clearObservedStartEvents");
			method.setAccessible(true);
			jeasync$clearObserved = method;
		}
		method.invoke(this);
	}

	@Unique
	private void jeasync$invokeStart() throws ReflectiveOperationException {
		Field field = jeasync$startRunnable;
		if (field == null) {
			field = StartEventObserver.class.getDeclaredField("startRunnable");
			field.setAccessible(true);
			jeasync$startRunnable = field;
		}
		((Runnable) field.get(this)).run();
	}

	@Unique
	private void jeasync$invokeStop() throws ReflectiveOperationException {
		Field field = jeasync$stopRunnable;
		if (field == null) {
			field = StartEventObserver.class.getDeclaredField("stopRunnable");
			field.setAccessible(true);
			jeasync$stopRunnable = field;
		}
		((Runnable) field.get(this)).run();
	}
}
