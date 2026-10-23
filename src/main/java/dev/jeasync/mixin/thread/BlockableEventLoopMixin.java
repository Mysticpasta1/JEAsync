package dev.jeasync.mixin.thread;

import dev.jeasync.JeiLoadProgress;
import dev.jeasync.RenderThreadGuard;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Stops a dispatched JEI plugin from running Minecraft's client task queue on a worker.
 * <p>
 * {@code Minecraft} extends {@code BlockableEventLoop}. That queue holds render-thread tasks from
 * other mods. Running one on a worker is the Distant Horizons {@code [GLProxy] was created outside
 * the render thread!} crash: the check throws inside {@code doRunTask}, which catches it, so the
 * plugin is never retried and the session dies.
 * <p>
 * Draining the queue off the client thread is refused. Queueing a task is refused only while the
 * client thread is inside the same plugin phase and cannot run it. During background startup the
 * client thread is free, so a plugin may queue a task and wait for it.
 * <p>
 * The guard fires only inside {@link RenderThreadGuard#enterPluginRegistration}, which wraps a
 * dispatched plugin callback. Vanilla's own calls, including the render thread, are unchanged.
 */
@Mixin(BlockableEventLoop.class)
public abstract class BlockableEventLoopMixin {

	/**
	 * Guards {@code pollTask}, where a queued task is handed to {@code doRunTask}. Mods reach this
	 * through access transformers, which is how the reported crash got here.
	 * <p>
	 * The task is left for the client thread. While that thread is inside the same plugin phase,
	 * the plugin fails. Otherwise the call reports that it ran nothing and pauses briefly, so a
	 * caller spinning on this method does not burn a core for the rest of startup.
	 */
	@Inject(method = "pollTask", at = @At("HEAD"), cancellable = true)
	private void jeasync$guardRunOneTask(CallbackInfoReturnable<Boolean> cir) {
		dev.jeasync.MixinApplied.mark("thread.BlockableEventLoopMixin");
		if (jeasync$offThreadClientLoop() && (JeiLoadProgress.isLoading() || RenderThreadGuard.isAsyncWorkerInPluginScope())) {
			if (RenderThreadGuard.isClientDispatch()) {
				RenderThreadGuard.refuseQueueDrain("run a queued client task");
			}
			LockSupport.parkNanos(100_000L);
			cir.setReturnValue(false);
		}
	}

	/**
	 * Guards {@code managedBlock}.
	 * <p>
	 * Off the client thread, during startup or a plugin callback, spinning here would drain the
	 * client queue. The call is cancelled and the caller waits without running those tasks. While
	 * the client thread is inside the same phase, that wait is refused and the plugin fails.
	 * On the render thread, Just Enough Resources' loot-loading spin is skipped while a plugin
	 * phase is running there. The stack walk runs only in that case.
	 */
	@Inject(method = "managedBlock", at = @At("HEAD"), cancellable = true)
	private void jeasync$guardWaitUntilNextTick(BooleanSupplier hasWork, CallbackInfo cir) {
		if (jeasync$offThreadClientLoop() && (JeiLoadProgress.isLoading() || RenderThreadGuard.isAsyncWorkerInPluginScope())) {
			if (RenderThreadGuard.isClientDispatch()) {
				RenderThreadGuard.refuseQueueDrain("wait for client work to run");
			}
			cir.cancel();
			while (!hasWork.getAsBoolean()) {
				LockSupport.parkNanos(100_000L);
			}
			return;
		}
		if (RenderThreadGuard.isClientDispatch() && Thread.currentThread().getName().equals("Render thread")) {
			for (StackTraceElement element : Thread.currentThread().getStackTrace()) {
				if (element.getClassName().startsWith("jeresources.")) {
					cir.cancel();
					return;
				}
			}
		}
		RenderThreadGuard.checkClientThreadExecutor("wait for client work to run");
	}

	private boolean jeasync$offThreadClientLoop() {
		BlockableEventLoop<?> loop = (BlockableEventLoop<?>) (Object) this;
		return "Client".equals(loop.name()) && !loop.isSameThread();
	}

	/**
	 * Guards {@code execute}. Another thread queues the task; it does not run it inline. The queue
	 * is refused only while the client thread is blocked in the same phase.
	 */
	@Inject(method = "execute", at = @At("HEAD"))
	private void jeasync$guardExecute(Runnable task, CallbackInfo cir) {
		RenderThreadGuard.checkClientThreadExecutor("execute a task on the client thread");
	}

	/**
	 * Guards {@code executeBlocking}, which queues a task and waits until the client thread runs it.
	 * That wait is refused while the client thread is blocked in the same phase.
	 */
	@Inject(method = "executeBlocking", at = @At("HEAD"))
	private void jeasync$guardSubmitAndWait(Runnable task, CallbackInfo cir) {
		RenderThreadGuard.checkClientThreadExecutor("submit a task and wait for it");
	}

	/**
	 * Guards {@code submit}. Waiting on the returned future deadlocks while the client thread is
	 * blocked in the same phase, so the submit itself is refused then.
	 */
	@Inject(method = "submit(Ljava/lang/Runnable;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
	private void jeasync$guardSubmit(Runnable task, CallbackInfoReturnable<CompletableFuture<Void>> cir) {
		RenderThreadGuard.checkClientThreadExecutor("submit a task to the client thread");
	}

	/**
	 * Guards the supplier form of {@code submit}. Same rule as the runnable form.
	 */
	@Inject(method = "submit(Ljava/util/function/Supplier;)Ljava/util/concurrent/CompletableFuture;", at = @At("HEAD"))
	private void jeasync$guardSupplyAsync(Supplier<?> task, CallbackInfoReturnable<CompletableFuture<?>> cir) {
		RenderThreadGuard.checkClientThreadExecutor("submit work to the client thread");
	}
}