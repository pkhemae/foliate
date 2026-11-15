package dev.foliate.core.scheduler;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * {@link Schedulers} for tests: nothing runs until the test calls {@link #tick}, and everything
 * runs on the test's own thread. Lets a module's logic be tested without a server, including
 * cancellation, which MockBukkit's schedulers do not support.
 *
 * <p>It behaves like the real thing as seen by a caller that owns nothing, such as a reload
 * running on an async thread: {@code isCurrent()} is false, so {@code executeOrRun} always defers
 * by a tick, and work for an entity that is gone is rejected, dropped or stopped as
 * {@link TaskContext} documents. A player counts as gone once offline, any other entity once
 * removed.
 *
 * <p>There are no regions here: every context runs on the same thread, so this proves nothing
 * about thread safety, nor that the right context was chosen.
 */
public final class ManualSchedulers implements Schedulers {

    private static final long MILLIS_PER_TICK = 50L;

    private final List<Task> tasks = new ArrayList<>();
    private final Set<Entity> retired = new HashSet<>();
    private final TaskContext shared = new ManualContext(() -> false);
    private long now;

    /**
     * Makes an entity count as gone although it still looks alive: the state of a player who is
     * disconnecting, still listed as online but whose scheduler no longer accepts work.
     */
    public void retire(Entity entity) {
        retired.add(entity);
    }

    /** Advances time by the given number of ticks, running the tasks that come due. */
    public void tick(int ticks) {
        for (int i = 0; i < ticks; i++) {
            now++;
            // Copy: a task may schedule or cancel others while it runs.
            for (Task task : List.copyOf(tasks)) {
                if (task.isLive() && task.dueAt <= now) {
                    task.run();
                }
            }
            tasks.removeIf(task -> !task.isLive());
        }
    }

    /** Number of tasks still scheduled, repeating ones included. */
    public int pendingTasks() {
        return (int) tasks.stream().filter(Task::isLive).count();
    }

    @Override
    public TaskContext async() {
        return shared;
    }

    @Override
    public TaskContext global() {
        return shared;
    }

    @Override
    public TaskContext region(Location location) {
        return shared;
    }

    @Override
    public TaskContext entity(Entity entity) {
        return new ManualContext(() -> retired.contains(entity)
                || (entity instanceof Player player ? !player.isOnline() : !entity.isValid()));
    }

    @Override
    public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
        return entity(entity).supply(() -> entity.teleport(destination)).exceptionally(retired -> false);
    }

    private static long ticks(Duration duration) {
        return Math.max(1L, duration.toMillis() / MILLIS_PER_TICK);
    }

    private final class ManualContext implements TaskContext {

        private final BooleanSupplier retired;

        ManualContext(BooleanSupplier retired) {
            this.retired = retired;
        }

        @Override
        public void execute(Runnable task) {
            if (retired.getAsBoolean()) {
                throw new EntityRetiredException();
            }
            schedule(task, () -> {}, 1L, 0L);
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return schedule(task, () -> {}, ticks(delay), 0L);
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return schedule(task, () -> {}, ticks(initialDelay), ticks(period));
        }

        @Override
        public boolean isCurrent() {
            return false;
        }

        @Override
        public void executeOrRun(Runnable task) {
            execute(task);
        }

        @Override
        public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
            CompletableFuture<T> future = new CompletableFuture<>();
            Runnable whenRetired = () -> future.completeExceptionally(new EntityRetiredException());
            if (retired.getAsBoolean()) {
                whenRetired.run();
                return future;
            }
            schedule(
                    () -> {
                        try {
                            future.complete(supplier.get());
                        } catch (RuntimeException e) {
                            future.completeExceptionally(e);
                        }
                    },
                    whenRetired,
                    1L,
                    0L);
            return future;
        }

        private Task schedule(Runnable runnable, Runnable whenRetired, long delay, long period) {
            Task task = new Task(runnable, whenRetired, retired, now + delay, period);
            if (retired.getAsBoolean()) {
                task.cancelled = true;
            } else {
                tasks.add(task);
            }
            return task;
        }
    }

    private static final class Task implements TaskHandle {

        private final Runnable runnable;
        private final Runnable whenRetired;
        private final BooleanSupplier retired;
        private final long period;
        private long dueAt;
        private boolean cancelled;
        private boolean finished;

        Task(Runnable runnable, Runnable whenRetired, BooleanSupplier retired, long dueAt, long period) {
            this.runnable = runnable;
            this.whenRetired = whenRetired;
            this.retired = retired;
            this.dueAt = dueAt;
            this.period = period;
        }

        boolean isLive() {
            return !cancelled && !finished;
        }

        void run() {
            if (retired.getAsBoolean()) {
                // The server cancels the task and runs its retired callback instead.
                cancelled = true;
                whenRetired.run();
                return;
            }
            if (period > 0) {
                dueAt += period;
            } else {
                finished = true;
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
