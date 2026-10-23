package dev.jeasync;

/**
 * Refuses plugin code that would run Minecraft's client task queue on a worker.
 * <p>
 * {@code Minecraft} is a {@code BlockableEventLoop}. Its queue holds render-thread tasks from other
 * mods. Running one on a worker is the Distant Horizons {@code [GLProxy] was created outside the
 * render thread!} crash. That check throws inside {@code doRunTask}, which catches it, so it never
 * reaches {@link AsyncPluginSupport} and the session dies instead of the plugin being retried.
 * <p>
 * Draining the queue off the client thread is refused. Queuing work is refused only while the client
 * thread is inside a plugin phase and cannot run the task. During background startup the client
 * thread is free, so a plugin may queue a task and wait for it. Code outside a plugin callback is
 * left alone.
 */
public final class RenderThreadGuard {
	private static final ThreadLocal<Scope> SCOPE = new ThreadLocal<>();
	private static volatile boolean clientDispatch;

	private RenderThreadGuard() {
	}

	/**
	 * Marks the calling thread as an async plugin-registration worker for as long as the current
	 * scope is open. Nestable, because a plugin callback can itself reach code that opens a scope.
	 */
	public static void enterPluginRegistration() {
		Scope current = SCOPE.get();
		SCOPE.set(new Scope(current));
	}

	public static void exitPluginRegistration() {
		Scope current = SCOPE.get();
		if (current == null) {
			return;
		}
		if (current.parent == null) {
			SCOPE.remove();
		} else {
			SCOPE.set(current.parent);
		}
	}

	/**
	 * Whether the calling thread is currently running a dispatched plugin's registration callback.
	 */
	public static boolean isAsyncWorkerInPluginScope() {
		return SCOPE.get() != null;
	}

	/**
	 * The client thread is inside {@code callOnPlugins} and is going to block until the phase ends.
	 * Workers must not wait on it.
	 */
	public static void enterClientDispatch() {
		clientDispatch = true;
	}

	public static void exitClientDispatch() {
		clientDispatch = false;
	}

	public static boolean isClientDispatch() {
		return clientDispatch;
	}

	/**
	 * Fails the plugin if it is about to run a task that was queued for the client thread.
	 */
	public static void refuseQueueDrain(String operation) {
		if (!isAsyncWorkerInPluginScope()) {
			return;
		}
		throw new OffThreadClientExecutorException(operation);
	}

	/**
	 * Fails the plugin if it is queueing client work while the client thread is blocked in the
	 * same phase. When the client thread is free, the call is left alone and the task is queued.
	 */
	public static void checkClientThreadExecutor(String operation) {
		if (!isAsyncWorkerInPluginScope() || !clientDispatch) {
			return;
		}
		throw new OffThreadClientExecutorException(operation);
	}

	/**
	 * Thrown to unwind a plugin that has reached into Minecraft's event loop from a worker thread.
	 * <p>
	 * Unchecked and deliberately narrow: it exists to travel up to
	 * {@link AsyncPluginSupport#markIncompatible}, which treats any {@link Throwable} from an async
	 * callback as proof the plugin cannot stay on a worker. It is never expected to be caught by a
	 * plugin, and a plugin that swallows it only defers the problem to the client-thread retry.
	 */
	public static final class OffThreadClientExecutorException extends RuntimeException {
		private OffThreadClientExecutorException(String operation) {
			super("A JEI plugin tried to " + operation + " from Minecraft's event loop on the async "
				+ "plugin-registration thread (" + Thread.currentThread().getName() + "). Any task run "
				+ "there may belong to the render thread. Failing this plugin so it is re-run on the "
				+ "client thread.");
		}
	}

	private static final class Scope {
		private final Scope parent;

		private Scope(Scope parent) {
			this.parent = parent;
		}
	}
}