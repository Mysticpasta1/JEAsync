package dev.jeasync;

import mezz.jei.api.IModPlugin;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Names the plugin a phase is still waiting on when it stops making progress.
 * <p>
 * A plugin callback can block forever on a monitor, a future or a client task. The phase then waits
 * on {@code CompletableFuture.allOf(...).join()} and never finishes, while the only normal record is
 * the "Plugin ... registered" line a callback writes after it returns, so a hang leaves no trace of
 * which plugin is still inside it. This keeps every dispatched callback on file with its worker
 * thread and, after a timeout, logs the still-running plugins with their stacks. When the JVM
 * reports a monitor deadlock it logs those threads too, including the lock each one is waiting on
 * and the thread that owns it.
 * <p>
 * This only observes. No callback is interrupted, failed, timed out or moved to another thread;
 * the phase still behaves exactly as before, and the watchdog thread is a daemon.
 * <p>
 * The delay is {@code stallReportSeconds} in the client config (default 120, 0 disables the
 * watchdog per phase). {@code -Djeasync.stallReportSeconds} overrides it, and a stuck callback is
 * reported again at that same interval for as long as it stays stuck.
 */
public final class PluginStallWatchdog {
	private static final Logger LOGGER = LogManager.getLogger();
	private static final Watch DISABLED = new Watch();
	private static volatile ScheduledExecutorService scheduler;

	private PluginStallWatchdog() {
	}

	/**
	 * Starts watching one plugin phase. The returned watch must be closed once the phase is done,
	 * whether it completed or threw.
	 * <p>
	 * When the configured delay is 0 this returns a shared no-op watch. No scheduler thread is
	 * created, no callback is tracked, and no lock or stack collection is allocated.
	 */
	public static Watch begin(String phase) {
		int seconds = AsyncConfig.stallReportSeconds();
		if (seconds <= 0) {
			return DISABLED;
		}
		return new Watch(phase, TimeUnit.SECONDS.toMillis(seconds));
	}

	private static ScheduledExecutorService scheduler() {
		ScheduledExecutorService current = scheduler;
		if (current != null) {
			return current;
		}
		synchronized (PluginStallWatchdog.class) {
			if (scheduler == null) {
				ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, task -> {
					Thread thread = new Thread(task, "jeasync Stall Watchdog");
					thread.setDaemon(true);
					thread.setContextClassLoader(PluginStallWatchdog.class.getClassLoader());
					return thread;
				});
				executor.setRemoveOnCancelPolicy(true);
				scheduler = executor;
			}
			return scheduler;
		}
	}

	/**
	 * One plugin phase's in-flight callbacks. The map is identity-keyed because plugin instances are
	 * unique and a plugin can override {@code equals} without meaning to merge itself with another.
	 */
	public static final class Watch implements AutoCloseable {
		private final String phase;
		private final long reportAfterMs;
		private final Map<IModPlugin, Entry> inFlight;
		private final ScheduledFuture<?> reporter;
		private final AtomicBoolean deadlockLogged;

		/** The shared disabled watch: no map, no atomics, no scheduled task. */
		private Watch() {
			this.phase = "disabled";
			this.reportAfterMs = 0L;
			this.inFlight = null;
			this.reporter = null;
			this.deadlockLogged = null;
		}

		private Watch(String phase, long reportAfterMs) {
			this.phase = phase;
			this.reportAfterMs = reportAfterMs;
			this.inFlight = Collections.synchronizedMap(new IdentityHashMap<>());
			this.deadlockLogged = new AtomicBoolean();
			this.reporter = scheduler().scheduleWithFixedDelay(this::report, reportAfterMs, reportAfterMs, TimeUnit.MILLISECONDS);
		}

		/** Records a callback that has been handed to a worker but may still be queued. */
		public void dispatched(IModPlugin plugin) {
			if (inFlight == null) {
				return;
			}
			inFlight.putIfAbsent(plugin, new Entry());
		}

		/** Records that the callback is now executing on {@code thread}. */
		public void started(IModPlugin plugin, Thread thread) {
			if (inFlight == null) {
				return;
			}
			Entry entry = inFlight.get(plugin);
			if (entry != null) {
				entry.start(thread);
			}
		}

		/** Removes a callback once it has returned, thrown, or been queued for a retry. */
		public void finished(IModPlugin plugin) {
			if (inFlight != null) {
				inFlight.remove(plugin);
			}
		}

		private void report() {
			try {
				long now = System.nanoTime();
				List<Stalled> stalled = new ArrayList<>();
				int queued = 0;
				synchronized (inFlight) {
					for (Map.Entry<IModPlugin, Entry> item : inFlight.entrySet()) {
						Entry entry = item.getValue();
						Thread thread = entry.thread();
						if (thread == null) {
							queued++;
							continue;
						}
						long elapsed = entry.elapsedMillis(now);
						// A callback starts just after the watch, so a report at exactly the
						// threshold can see it a few milliseconds short and wait a whole interval.
						if (elapsed + 1000L >= reportAfterMs) {
							stalled.add(new Stalled(item.getKey(), thread, elapsed));
						}
					}
				}
				if (stalled.isEmpty()) {
					return;
				}
				stalled.sort(Comparator.comparingLong(Stalled::elapsedMillis).reversed());
				LOGGER.warn(
					"jeasync: plugin phase '{}' has not finished; {} plugin callback(s) are still running and {} are queued",
					phase,
					stalled.size(),
					queued);
				ThreadMXBean threads = ManagementFactory.getThreadMXBean();
				for (Stalled item : stalled) {
					logStalled(threads, item);
				}
				logAllThreads(threads);
				if (deadlockLogged.compareAndSet(false, true)) {
					logDeadlocks(threads);
				}
			} catch (Throwable t) {
				LOGGER.warn("jeasync: could not report the stalled plugin phase '{}'", phase, t);
			}
		}

		private void logStalled(ThreadMXBean threads, Stalled item) {
			Thread thread = item.thread();
			long elapsedSeconds = item.elapsedMillis() / 1000L;
			String pluginUid = String.valueOf(item.plugin().getPluginUid());
			StringBuilder stack = new StringBuilder();
			for (StackTraceElement element : thread.getStackTrace()) {
				stack.append("\n\tat ").append(element);
			}
			ThreadInfo info = threads.getThreadInfo(thread.getId(), Integer.MAX_VALUE);
			String owner = info == null ? "unavailable" : describeOwner(info);
			LOGGER.warn(
				"jeasync: plugin {} ({}) has been inside '{}' for {}s on thread '{}' (state {}, {}){}",
				pluginUid,
				item.plugin().getClass().getName(),
				phase,
				elapsedSeconds,
				thread.getName(),
				thread.getState(),
				owner,
				stack);
		}

		private static String describeOwner(ThreadInfo info) {
			if (info.getLockOwnerName() != null) {
				return "blocked on " + info.getLockName() + " owned by thread '" + info.getLockOwnerName() + "'";
			}
			if (info.getLockName() != null) {
				return "waiting on " + info.getLockName();
			}
			return "not waiting on a monitor";
		}

		private void logAllThreads(ThreadMXBean threads) {
			ThreadInfo[] infos = threads.getThreadInfo(threads.getAllThreadIds(), true, true, 64);
			StringBuilder dump = new StringBuilder();
			for (ThreadInfo info : infos) {
				if (info != null) {
					appendThread(dump, info);
				}
			}
			LOGGER.warn("jeasync: thread dump during plugin phase '{}':{}", phase, dump);
		}

		private void logDeadlocks(ThreadMXBean threads) {
			long[] deadlocked = threads.findDeadlockedThreads();
			if (deadlocked == null || deadlocked.length == 0) {
				return;
			}
			ThreadInfo[] infos = threads.getThreadInfo(deadlocked, true, true);
			StringBuilder report = new StringBuilder();
			for (ThreadInfo info : infos) {
				if (info != null) {
					appendThread(report, info);
				}
			}
			LOGGER.warn("jeasync: JVM monitor deadlock during plugin phase '{}':{}", phase, report);
		}

		private static void appendThread(StringBuilder report, ThreadInfo info) {
			report.append("\n\"").append(info.getThreadName()).append("\" ").append(info.getThreadState());
			if (info.getLockName() != null) {
				report.append(" on ").append(info.getLockName());
				if (info.getLockOwnerName() != null) {
					report.append(" owned by \"").append(info.getLockOwnerName()).append('"');
				}
			}
			for (StackTraceElement element : info.getStackTrace()) {
				report.append("\n\tat ").append(element);
			}
		}

		@Override
		public void close() {
			if (inFlight != null) {
				inFlight.clear();
			}
			if (reporter != null) {
				reporter.cancel(false);
			}
		}
	}

	private static final class Entry {
		private volatile Thread thread;
		private volatile long startedAtNanos;

		private void start(Thread thread) {
			this.thread = thread;
			this.startedAtNanos = System.nanoTime();
		}

		private Thread thread() {
			return thread;
		}

		private long elapsedMillis(long now) {
			return startedAtNanos == 0L ? 0L : TimeUnit.NANOSECONDS.toMillis(now - startedAtNanos);
		}
	}

	private record Stalled(IModPlugin plugin, Thread thread, long elapsedMillis) {
	}
}
