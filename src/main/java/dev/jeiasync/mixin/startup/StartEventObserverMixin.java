package dev.jeiasync.mixin.startup;

import dev.jeiasync.JeiLoadProgress;
import dev.jeiasync.MixinApplied;
import dev.jeiasync.WorkerEnvironment;
import mezz.jei.common.Internal;
import mezz.jei.neoforge.startup.StartEventObserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Runs JEI's startup off the render thread.
 * <p>
 * {@code transitionState} is the single place startup and shutdown go through, including the
 * container-screen path that force-starts JEI when a screen opens early. The render thread marks
 * JEI started and returns. The worker runs {@code startRunnable}. A second start while that worker
 * is busy is ignored. Waiting for it on the render thread deadlocks a plugin that queued client
 * work, which is how an early inventory open used to hang or crash.
 * <p>
 * Login starts JEI without waiting for {@code RecipesUpdatedEvent}. On a large pack that event
 * can arrive long after the player can open a screen, and a failed recipe packet never sends it.
 * JEI then stays idle until the inventory opens and force-starts from that screen. A recipe sync
 * that arrives afterwards still restarts JEI, which is the path that picks up the server recipes.
 * <p>
 * A disconnect during startup is applied after the worker finishes. A restart requested during
 * startup (synced recipes arriving) stops and starts once more on that same worker.
 * <p>
 * Targets upstream 19.69.0.456. {@code startIfReady} waits on {@code observedRecipeSync} inline.
 * Treating that flag as already set starts JEI from the login event.
 */
@Mixin(value = StartEventObserver.class, remap = false)
public abstract class StartEventObserverMixin {
	private static final Logger LOGGER = LogManager.getLogger();
	private static boolean notedEarlyStart;

	/**
	 * {@code startIfReady} returns while {@code observedRecipeSync} is still false. Reporting the
	 * flag as set starts JEI from the login event. The recipe event's restart path is unchanged.
	 */
	@Redirect(
		method = "startIfReady",
		at = @At(
			value = "FIELD",
			target = "Lmezz/jei/neoforge/startup/StartEventObserver;observedRecipeSync:Z",
			opcode = Opcodes.GETFIELD
		),
		remap = false
	)
	private boolean jeiasync$startBeforeRecipeSync(StartEventObserver observer) {
		if (!notedEarlyStart) {
			notedEarlyStart = true;
			LOGGER.info("JEI Async: starting at login. A later server recipe sync restarts JEI.");
		}
		return false;
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
			invokeStart();
			int restarts = 0;
			while (JeiLoadProgress.consumeRestart() && restarts++ < 2) {
				invokeStop();
				invokeStart();
			}
			if (JeiLoadProgress.consumeStop()) {
				JeiLoadProgress.dropDeferredGui();
				assignListening();
				if (Internal.getOptionalJeiRuntime().isPresent()) {
					invokeStop();
				}
				JeiLoadProgress.finish();
				return;
			}
			if (Internal.getOptionalJeiRuntime().isPresent()) {
				JeiLoadProgress.succeed();
			} else {
				LOGGER.error("JEI Async: startup finished without a runtime");
				JeiLoadProgress.dropDeferredGui();
				JeiLoadProgress.fail("JEI failed to load");
			}
		} catch (Throwable t) {
			LOGGER.error("JEI Async: startup failed", t);
			JeiLoadProgress.dropDeferredGui();
			JeiLoadProgress.fail("JEI failed to load");
		}
	}

	private void assignState(Object newState) throws ReflectiveOperationException {
		Field field = StartEventObserver.class.getDeclaredField("state");
		field.setAccessible(true);
		field.set(this, newState);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private void assignListening() throws ReflectiveOperationException {
		Class type = Class.forName("mezz.jei.neoforge.startup.StartEventObserver$State");
		assignState(Enum.valueOf(type, "LISTENING"));
	}

	private void clearObserved() throws ReflectiveOperationException {
		Method method = StartEventObserver.class.getDeclaredMethod("clearObservedStartEvents");
		method.setAccessible(true);
		method.invoke(this);
	}

	private void invokeStart() throws ReflectiveOperationException {
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
