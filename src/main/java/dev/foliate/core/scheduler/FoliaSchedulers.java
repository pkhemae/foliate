package dev.foliate.core.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * {@link Schedulers} backed by the server's region, entity, global and async schedulers. They exist
 * on Paper too, where everything simply runs on the main thread.
 *
 * <p>Thread-safe.
 */
public final class FoliaSchedulers implements Schedulers {

    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final Server server;
    private final Logger logger;
    private final Set<ScheduledTask> timers = ConcurrentHashMap.newKeySet();
    private final TaskContext async = new AsyncContext();
    private final TaskContext global = new GlobalContext();

    public FoliaSchedulers(Plugin plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getSLF4JLogger();
    }

    @Override
    public TaskContext async() {
        return async;
    }

    @Override
    public TaskContext global() {
        return global;
    }

    @Override
    public TaskContext region(Location location) {
        return new RegionContext(location.clone());
    }

    @Override
    public TaskContext entity(Entity entity) {
        return new EntityContext(entity);
    }

    @Override
    public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
        Location target = destination.clone();
        TaskContext context = entity(entity);
        if (context.isCurrent()) {
            return entity.teleportAsync(target);
        }
        // The future that teleportAsync gives is composed on the next line, not left unobserved.
        @SuppressWarnings("FutureReturnValueIgnored")
        CompletableFuture<CompletableFuture<Boolean>> started = context.supply(() -> entity.teleportAsync(target));
        return started
                .thenCompose(future -> future)
                .exceptionally(error -> {
                    Throwable cause = error instanceof CompletionException && error.getCause() != null
                            ? error.getCause()
                            : error;
                    if (cause instanceof EntityRetiredException) {
                        return false;
                    }
                    throw new CompletionException(cause);
                });
    }

    /**
     * Cancels every repeating task and all pending global and async tasks. Called first thing in
     * {@code onDisable}, so that no timer fires while the modules release their resources.
     *
     * <p>Futures of tasks that had not started yet are left incomplete: nothing may wait on them
     * during shutdown.
     */
    public void shutdown() {
        timers.forEach(ScheduledTask::cancel);
        timers.clear();
        server.getGlobalRegionScheduler().cancelTasks(plugin);
        server.getAsyncScheduler().cancelTasks(plugin);
    }

    private static long ticks(Duration duration) {
        return Math.max(1L, duration.toMillis() / MILLIS_PER_TICK);
    }

    private static long millis(Duration duration) {
        return Math.max(0L, duration.toMillis());
    }

    /**
     * Wraps a task so that a failure is logged instead of vanishing in a scheduler thread. An
     * {@link EntityRetiredException} is an expected outcome, not a failure, and is not logged.
     */
    private Runnable logged(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (EntityRetiredException expected) {
                // The task tried to reach an entity that is gone, typically a player who left.
            } catch (Throwable t) {
                logger.error("Unhandled exception in scheduled task", t);
            }
        };
    }

    private Consumer<ScheduledTask> consumer(Runnable task) {
        Runnable logged = logged(task);
        return scheduled -> logged.run();
    }

    private static <T> Runnable completing(CompletableFuture<T> future, Supplier<T> supplier) {
        return () -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        };
    }

    private TaskHandle handle(@Nullable ScheduledTask task) {
        return task == null ? CancelledHandle.INSTANCE : new ScheduledHandle(task);
    }

    private TaskHandle timer(ScheduledTask task) {
        timers.add(task);
        return new ScheduledHandle(task);
    }

    /** Behaviour shared by every context. */
    private abstract class Context implements TaskContext {

        @Override
        public final void executeOrRun(Runnable task) {
            if (isCurrent()) {
                logged(task).run();
            } else {
                execute(task);
            }
        }
    }

    private final class AsyncContext extends Context {

        @Override
        public void execute(Runnable task) {
            server.getAsyncScheduler().runNow(plugin, consumer(task));
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return handle(server.getAsyncScheduler()
                    .runDelayed(plugin, consumer(task), millis(delay), TimeUnit.MILLISECONDS));
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return timer(server.getAsyncScheduler()
                    .runAtFixedRate(
                            plugin,
                            consumer(task),
                            millis(initialDelay),
                            Math.max(1L, period.toMillis()),
                            TimeUnit.MILLISECONDS));
        }

        @Override
        public boolean isCurrent() {
            return false;
        }

        @Override
        public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
            CompletableFuture<T> future = new CompletableFuture<>();
            server.getAsyncScheduler().runNow(plugin, scheduled -> completing(future, supplier).run());
            return future;
        }
    }

    private final class GlobalContext extends Context {

        @Override
        public void execute(Runnable task) {
            server.getGlobalRegionScheduler().execute(plugin, logged(task));
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return handle(server.getGlobalRegionScheduler().runDelayed(plugin, consumer(task), ticks(delay)));
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return timer(server.getGlobalRegionScheduler()
                    .runAtFixedRate(plugin, consumer(task), ticks(initialDelay), ticks(period)));
        }

        @Override
        public boolean isCurrent() {
            return server.isGlobalTickThread();
        }

        @Override
        public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
            CompletableFuture<T> future = new CompletableFuture<>();
            server.getGlobalRegionScheduler().execute(plugin, completing(future, supplier));
            return future;
        }
    }

    private final class RegionContext extends Context {

        private final Location location;

        RegionContext(Location location) {
            this.location = location;
        }

        @Override
        public void execute(Runnable task) {
            server.getRegionScheduler().execute(plugin, location, logged(task));
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return handle(server.getRegionScheduler().runDelayed(plugin, location, consumer(task), ticks(delay)));
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return timer(server.getRegionScheduler()
                    .runAtFixedRate(plugin, location, consumer(task), ticks(initialDelay), ticks(period)));
        }

        @Override
        public boolean isCurrent() {
            return server.isOwnedByCurrentRegion(location);
        }

        @Override
        public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
            CompletableFuture<T> future = new CompletableFuture<>();
            server.getRegionScheduler().execute(plugin, location, completing(future, supplier));
            return future;
        }
    }

    private final class EntityContext extends Context {

        private final Entity entity;

        EntityContext(Entity entity) {
            this.entity = entity;
        }

        @Override
        public void execute(Runnable task) {
            if (!entity.getScheduler().execute(plugin, logged(task), null, 1L)) {
                throw new EntityRetiredException();
            }
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return handle(entity.getScheduler().runDelayed(plugin, consumer(task), null, ticks(delay)));
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            // The server cancels the task by itself when the entity is removed. Without this
            // callback the task, and through it the entity, would stay referenced by the timer set
            // until shutdown.
            AtomicReference<@Nullable ScheduledTask> tracked = new AtomicReference<>();
            // The callback can run before the task is tracked, and the task's state when it does is
            // the server's business: remember it here instead of asking the task afterwards.
            AtomicBoolean gone = new AtomicBoolean();
            Runnable retired = () -> {
                gone.set(true);
                ScheduledTask timer = tracked.get();
                if (timer != null) {
                    timers.remove(timer);
                }
            };
            ScheduledTask scheduled = entity.getScheduler()
                    .runAtFixedRate(plugin, consumer(task), retired, ticks(initialDelay), ticks(period));
            if (scheduled == null) {
                return CancelledHandle.INSTANCE;
            }
            tracked.set(scheduled);
            TaskHandle handle = timer(scheduled);
            if (gone.get() || scheduled.isCancelled()) {
                // The entity was removed between scheduling and tracking. Reported as cancelled
                // whatever the task says, so that callers see it will never run.
                timers.remove(scheduled);
                return CancelledHandle.INSTANCE;
            }
            return handle;
        }

        @Override
        public boolean isCurrent() {
            return server.isOwnedByCurrentRegion(entity);
        }

        @Override
        public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
            CompletableFuture<T> future = new CompletableFuture<>();
            Runnable retired = () -> future.completeExceptionally(new EntityRetiredException());
            if (!entity.getScheduler().execute(plugin, completing(future, supplier), retired, 1L)) {
                retired.run();
            }
            return future;
        }
    }

    private final class ScheduledHandle implements TaskHandle {

        private final ScheduledTask task;

        ScheduledHandle(ScheduledTask task) {
            this.task = task;
        }

        @Override
        public void cancel() {
            task.cancel();
            timers.remove(task);
        }

        @Override
        public boolean isCancelled() {
            return task.isCancelled();
        }
    }

    /** Handle for a task that could not be scheduled because its entity was already gone. */
    private enum CancelledHandle implements TaskHandle {
        INSTANCE;

        @Override
        public void cancel() {}

        @Override
        public boolean isCancelled() {
            return true;
        }
    }
}
