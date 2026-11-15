package dev.foliate.core.scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;

/**
 * {@link Schedulers} for tests: nothing runs until the test says so, and everything runs on the
 * test's own thread. Lets a module's logic be tested without a server, including cancellation,
 * which MockBukkit's schedulers do not support.
 *
 * <p>Every context behaves the same: there are no regions here, so this proves nothing about
 * thread safety.
 */
public final class ManualSchedulers implements Schedulers {

    private static final long MILLIS_PER_TICK = 50L;

    private final List<Task> tasks = new ArrayList<>();
    private final TaskContext context = new ManualContext();
    private long now;

    /** Advances time by the given number of ticks, running the tasks that come due. */
    public void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            now++;
            // Copy: a task may schedule or cancel others while it runs.
            for (Task task : List.copyOf(tasks)) {
                if (!task.cancelled && task.dueAt <= now) {
                    task.run();
                }
            }
            tasks.removeIf(task -> task.cancelled);
        }
    }

    /** Number of tasks still scheduled, repeating ones included. */
    public int pendingTasks() {
        return (int) tasks.stream().filter(task -> !task.cancelled).count();
    }

    @Override
    public TaskContext async() {
        return context;
    }

    @Override
    public TaskContext global() {
        return context;
    }

    @Override
    public TaskContext region(Location location) {
        return context;
    }

    @Override
    public TaskContext entity(Entity entity) {
        return context;
    }

    @Override
    public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
        return context.supply(() -> entity.teleport(destination));
    }

    private static long ticks(Duration duration) {
        return Math.max(1L, duration.toMillis() / MILLIS_PER_TICK);
    }

    private final class ManualContext implements TaskContext {

        @Override
        public void execute(Runnable task) {
            schedule(task, 1L, 0L);
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return schedule(task, ticks(delay), 0L);
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return schedule(task, ticks(initialDelay), ticks(period));
        }

        @Override
        public boolean isCurrent() {
            return true;
        }

        @Override
        public void executeOrRun(Runnable task) {
            task.run();
        }

        @Override
        public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
            CompletableFuture<T> future = new CompletableFuture<>();
            execute(() -> {
                try {
                    future.complete(supplier.get());
                } catch (RuntimeException e) {
                    future.completeExceptionally(e);
                }
            });
            return future;
        }

        private Task schedule(Runnable runnable, long delay, long period) {
            Task task = new Task(runnable, now + delay, period);
            tasks.add(task);
            return task;
        }
    }

    private static final class Task implements TaskHandle {

        private final Runnable runnable;
        private final long period;
        private long dueAt;
        private boolean cancelled;

        Task(Runnable runnable, long dueAt, long period) {
            this.runnable = runnable;
            this.dueAt = dueAt;
            this.period = period;
        }

        void run() {
            if (period > 0) {
                dueAt += period;
            } else {
                cancelled = true;
            }
            runnable.run();
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }
    }
}
