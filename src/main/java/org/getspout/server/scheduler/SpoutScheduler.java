package org.getspout.server.scheduler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import org.getspout.api.plugin.Plugin;
import org.getspout.api.scheduler.Scheduler;
import org.getspout.api.scheduler.SnapshotLock;
import org.getspout.api.scheduler.Task;
import org.getspout.api.scheduler.Worker;
import org.getspout.api.util.thread.DelayedWrite;
import org.getspout.server.SpoutServer;
import org.getspout.server.util.thread.AsyncExecutor;
import org.getspout.server.util.thread.AsyncExecutorUtils;
import org.getspout.server.util.thread.ThreadsafetyManager;
import org.getspout.server.util.thread.lock.SpoutSnapshotLock;
import org.getspout.server.util.thread.snapshotable.SnapshotManager;
import org.getspout.server.util.thread.snapshotable.SnapshotableArrayList;

/**
 * A class which schedules {@link SpoutTask}s.
 *
 * @author Graham Edgecombe
 */
public final class SpoutScheduler implements Scheduler {
	/**
	 * The number of milliseconds between pulses.
	 */
	private static final int PULSE_EVERY = 50;

	/**
	 * The server this scheduler is managing for.
	 */
	private final SpoutServer server;

	/**
	 * The scheduled executor service which backs this scheduler.
	 */
	private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

	/**
	 * A list of new tasks to be added.
	 */
	private final List<SpoutTask> newTasks = new ArrayList<SpoutTask>();

	/**
	 * A list of tasks to be removed.
	 */
	private final List<SpoutTask> oldTasks = new ArrayList<SpoutTask>();

	/**
	 * A list of active tasks.
	 */
	private final List<SpoutTask> tasks = new ArrayList<SpoutTask>();

	private final List<SpoutWorker> activeWorkers = Collections.synchronizedList(new ArrayList<SpoutWorker>());
	
	/**
	 * A snapshot manager for local snapshot variables
	 */
	private final SnapshotManager snapshotManager = new SnapshotManager();
	
	/**
	 * A list of all AsyncManagers
	 */
	private final SnapshotableArrayList<AsyncExecutor> asyncExecutors = new SnapshotableArrayList<AsyncExecutor>(snapshotManager, null);
	
	private volatile boolean shutdown = false;
	
	private final SpoutSnapshotLock snapshotLock = new SpoutSnapshotLock();
	
	private final Thread mainThread;
	
	/**
	 * Creates a new task scheduler.
	 */
	public SpoutScheduler(SpoutServer server) {
		this.server = server;

		mainThread = new MainThread();
	}

	private class MainThread extends Thread {

		public MainThread() {
			super("MainThread");
			ThreadsafetyManager.setMainThread(this);
		}

		public void run() {
			long targetPeriod = PULSE_EVERY;
			long lastTick = System.currentTimeMillis();

			while (!shutdown) {
				long startTime = System.currentTimeMillis();
				long delta = startTime - lastTick;
				try {
					if (!tick(delta)) {
						throw new IllegalStateException("Attempt made to start a tick before the previous one ended");
					}
					lastTick = startTime;
				} catch (Exception ex) {
					SpoutServer.logger.log(Level.SEVERE, "Error while pulsing: {0}", ex.getMessage());
					ex.printStackTrace();
				}
				long finishTime = System.currentTimeMillis();
				long freeTime = targetPeriod - (finishTime - startTime);

				if (freeTime > 0) {
					try {
						Thread.sleep(freeTime);
					} catch (InterruptedException e) {
						shutdown = true;
					}
				}
			}

			asyncExecutors.copySnapshot();

			// Halt all executors, except the Server

			for (AsyncExecutor e : asyncExecutors.get()) {
				if (!(e.getManager() instanceof SpoutServer)) {
					if (!e.haltExecutor()) {
						throw new IllegalStateException("Unable to halt executor for " + e.getManager());
					}
				}
			}

			try {
				copySnapshot(asyncExecutors.get(), true);
			} catch (InterruptedException ex) {
				SpoutServer.logger.log(Level.SEVERE, "Error while halting all executors: {0}", ex.getMessage());
			}

			asyncExecutors.copySnapshot();

			// Halt the executor for the Server

			for (AsyncExecutor e : asyncExecutors.get()) {
				if (!(e.getManager() instanceof SpoutServer)) {
					throw new IllegalStateException("Only the server should be left to shutdown");
				} else {
					if (!e.haltExecutor()) {
						throw new IllegalStateException("Unable to halt SpoutServer executor");
					}
				}
			}

			try {
				copySnapshot(asyncExecutors.get(), true);
			} catch (InterruptedException ex) {
				SpoutServer.logger.log(Level.SEVERE, "Error while shutting down server: {0}", ex.getMessage());
			}

		}

	}

	public void startMainThread() {
		if (mainThread.isAlive()) {
			throw new IllegalStateException("Attempt was made to start the main thread twice");
		} else {
			mainThread.start();
		}
	}

	/**
	 * Adds an async manager to the scheduler
	 */
	@DelayedWrite
	public void addAsyncExecutor(AsyncExecutor manager) {
		asyncExecutors.add(manager);
	}

	/**
	 * Removes an async manager from the scheduler
	 */
	@DelayedWrite
	public void removeAsyncExecutor(AsyncExecutor manager) {
		asyncExecutors.remove(manager);
	}

	/**
	 * Stops the scheduler and all tasks.
	 */
	public void stop() {
		cancelAllTasks();
		shutdown = true;
	}

	/**
	 * Schedules the specified task.
	 *
	 * @param task The task.
	 */
	private int schedule(SpoutTask task) {
		synchronized (newTasks) {
			newTasks.add(task);
		}
		return task.getTaskId();
	}

	/**
	 * Adds new tasks and updates existing tasks, removing them if necessary.
	 */
	private boolean tick(long delta) throws InterruptedException {
		
		asyncExecutors.copySnapshot();

		// Bring in new tasks this tick.
		synchronized (newTasks) {
			for (SpoutTask task : newTasks) {
				tasks.add(task);
			}
			newTasks.clear();
		}

		// Remove old tasks this tick.
		synchronized (oldTasks) {
			for (SpoutTask task : oldTasks) {
				tasks.remove(task);
			}
			oldTasks.clear();
		}

		// Run the relevant tasks.
		for (Iterator<SpoutTask> it = tasks.iterator(); it.hasNext(); ) {
			SpoutTask task = it.next();
			boolean cont = false;
			try {
				if (task.isSync()) {
					cont = task.pulse();
				} else {
					activeWorkers.add(new SpoutWorker(task, this));
				}
			} finally {
				if (!cont) {
					it.remove();
				}
			}
		}

		List<AsyncExecutor> executors = asyncExecutors.get();

		int stage = 0;
		boolean allStagesComplete = false;

		boolean joined = false;

		while (!allStagesComplete) {

			allStagesComplete = true;

			for (AsyncExecutor e : executors) {
				if (stage < e.getManager().getStages()) {
					allStagesComplete = false;
					if (!e.startTick(stage, delta)) {
						return false;
					}
				} else {
					continue;
				}
			}

			joined = false;

			while (!joined && !shutdown) {
				try {
					AsyncExecutorUtils.pulseJoinAll(executors, (long) (PULSE_EVERY << 4));
					joined = true;
				} catch (TimeoutException e) {
					server.getLogger().info("Tick had not completed after " + (PULSE_EVERY << 4) + "ms");
				}
			}

			stage++;
		}

		copySnapshot(executors, false);
		
		((SpoutServer)server).processUnloads();
		
		return true;
	}

	private void copySnapshot(List<AsyncExecutor> executors, boolean alreadyShutdown) throws InterruptedException {
		lockSnapshotLock();

		try {
			for (AsyncExecutor e : executors) {
				if (!e.preSnapshot()) {
					throw new IllegalStateException("Attempt made to copy the snapshot for a tick while the previous operation was still active");
				}
			}

			boolean joined = false;

			while (!joined && !(shutdown && (!alreadyShutdown))) {
				try {
					AsyncExecutorUtils.pulseJoinAll(executors, (long) (PULSE_EVERY << 4));
					joined = true;
				} catch (TimeoutException e) {
					server.getLogger().info("Tick had not completed after " + (PULSE_EVERY << 4) + "ms");
				}
			}

			for (AsyncExecutor e : executors) {
				if (!e.copySnapshot()) {
					throw new IllegalStateException("Attempt made to copy the snapshot for a tick while the previous operation was still active");
				}
			}

			joined = false;

			while (!joined && !(shutdown && (!alreadyShutdown))) {
				try {
					AsyncExecutorUtils.pulseJoinAll(executors, (long) (PULSE_EVERY << 4));
					joined = true;
				} catch (TimeoutException e) {
					server.getLogger().info("Tick had not completed after " + (PULSE_EVERY << 4) + "ms");
				}
			}
		} finally {
			unlockSnapshotLock();
		}
	}

	private void lockSnapshotLock() {

		int delay = 500;
		int threshold = 50;

		long startTime = System.currentTimeMillis();

		boolean success = false;

		while (!success) {
			success = snapshotLock.writeLock(delay);
			if (!success) {
				delay *= 1.5;
				List<Plugin> violatingPlugins = snapshotLock.getLockingPlugins(threshold);
				server.getLogger().info("Unable to lock snapshot after " + (System.currentTimeMillis() - startTime) + "ms");
				for (Plugin p : violatingPlugins) {
					server.getLogFile().indexOf(p.getDescription().getName() + " has locked the snapshot lock for more than " + threshold + "ms");
				}
			}
		}
	}

	private void unlockSnapshotLock() {
		snapshotLock.writeUnlock();
	}

	@Override
	public int scheduleSyncDelayedTask(Plugin plugin, Runnable task, long delay) {
		return scheduleSyncRepeatingTask(plugin, task, delay, -1);
	}

	@Override
	public int scheduleSyncDelayedTask(Plugin plugin, Runnable task) {
		return scheduleSyncDelayedTask(plugin, task, 0);
	}

	@Override
	public int scheduleSyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
		return schedule(new SpoutTask(plugin, task, true, delay, period));
	}

	@Override
	public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task, long delay) {
		return scheduleAsyncRepeatingTask(plugin, task, delay, -1);
	}

	@Override
	public int scheduleAsyncDelayedTask(Plugin plugin, Runnable task) {
		return scheduleAsyncRepeatingTask(plugin, task, 0, -1);
	}

	@Override
	public int scheduleAsyncRepeatingTask(Plugin plugin, Runnable task, long delay, long period) {
		return schedule(new SpoutTask(plugin, task, false, delay, period));
	}

	@Override
	public <T> Future<T> callSyncMethod(Plugin plugin, Callable<T> task) {
		throw new UnsupportedOperationException("Not supported yet.");
	}

	@Override
	public void cancelTask(int taskId) {
		synchronized (oldTasks) {
			for (SpoutTask task : tasks) {
				if (task.getTaskId() == taskId) {
					oldTasks.add(task);
					return;
				}
			}
		}
	}

	@Override
	public void cancelTasks(Plugin plugin) {
		synchronized (oldTasks) {
			for (SpoutTask task : tasks) {
				if (task.getOwner() == plugin) {
					oldTasks.add(task);
				}
			}
		}
	}

	@Override
	public void cancelAllTasks() {
		synchronized (oldTasks) {
			for (SpoutTask spoutTask : tasks) {
				oldTasks.add(spoutTask);
			}
		}
	}

	@Override
	public List<Worker> getActiveWorkers() {
		return new ArrayList<Worker>(activeWorkers);
	}

	@Override
	public List<Task> getPendingTasks() {
		ArrayList<Task> result = new ArrayList<Task>();
		for (SpoutTask spoutTask : tasks) {
			result.add(spoutTask);
		}
		return result;
	}

	synchronized void workerComplete(SpoutWorker worker) {
		activeWorkers.remove(worker);
		if (!worker.shouldContinue()) {
			oldTasks.add(worker.getTask());
		}
	}

	public SnapshotLock getSnapshotLock() {
		return snapshotLock;
	}
}

