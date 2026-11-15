package dev.foliate.core.scheduler;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The only way this plugin schedules work. Folia has no main thread: pick the context that owns
 * what the task touches.
 *
 * <ul>
 *   <li>{@link #entity(Entity)}: anything about one entity or player. Follows it across regions,
 *       and only runs while the entity is loaded.
 *   <li>{@link #region(Location)}: blocks and chunks at a location. Not for entities.
 *   <li>{@link #global()}: world-wide state such as time, weather, game rules.
 *   <li>{@link #async()}: blocking I/O. Never touches the world.
 * </ul>
 *
 * <p>Thread-safe. {@code BukkitScheduler} must never be used: it does not work on Folia.
 */
public final class Schedulers {

    private static final long MILLIS_PER_TICK = 50L;

    private final Plugin plugin;
    private final Server server;
    private final Logger logger;
    private final Set<ScheduledTask> timers = ConcurrentHashMap.newKeySet();
    private final TaskContext async = new AsyncContext();
    private final TaskContext global = new GlobalContext();

    public Schedulers(Plugin plugin) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.logger = plugin.getSLF4JLogger();
    }

    public TaskContext async() {
        return async;
    }

    public TaskContext global() {
        return global;
    }

    public TaskContext region(Location location) {
        return new RegionContext(location.clone());
    }

    public TaskContext entity(Entity entity) {
        return new EntityContext(entity);
    }

    /**
     * Teleports an entity from any thread. {@code Entity#teleport} is broken on Folia, and
     * {@code teleportAsync} must be called from the region owning the entity: this does both.
     *
     * <p>Never call {@code join()} or {@code get()} on the result from a tick thread.
     */
    public CompletableFuture<Boolean> teleport(Entity entity, Location destination) {
        Location target = destination.clone();
        TaskContext context = entity(entity);
        if (context.isCurrent()) {
            return entity.teleportAsync(target);
        }
        return context.supply(() -> entity.teleportAsync(target)).thenCompose(future -> future);
    }

    /**
     * Cancels every repeating task and all pending global and async tasks. Called once from
     * {@code onDisable}, after the modules are disabled.
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

    /** Wraps a task so that a failure is logged instead of vanishing in a scheduler thread. */
    private Runnable logged(Runnable task) {
        return () -> {
            try {
                task.run();
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

    private TaskHandle handle(@Nullable ScheduledTask task, boolean repeating) {
        if (task == null) {
            return CancelledHandle.INSTANCE;
        }
        if (repeating) {
            timers.add(task);
        }
        return new ScheduledHandle(task);
    }

    private final class AsyncContext implements TaskContext {

        @Override
        public void execute(Runnable task) {
            server.getAsyncScheduler().runNow(plugin, consumer(task));
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return handle(
                    server.getAsyncScheduler()
                            .runDelayed(plugin, consumer(task), delay.toMillis(), TimeUnit.MILLISECONDS),
                    false);
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return handle(
                    server.getAsyncScheduler()
                            .runAtFixedRate(
                                    plugin,
                                    consumer(task),
                                    initialDelay.toMillis(),
                                    Math.max(1L, period.toMillis()),
                                    TimeUnit.MILLISECONDS),
                    true);
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

    private final class GlobalContext implements TaskContext {

        @Override
        public void execute(Runnable task) {
            server.getGlobalRegionScheduler().execute(plugin, logged(task));
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return handle(server.getGlobalRegionScheduler().runDelayed(plugin, consumer(task), ticks(delay)), false);
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return handle(
                    server.getGlobalRegionScheduler()
                            .runAtFixedRate(plugin, consumer(task), ticks(initialDelay), ticks(period)),
                    true);
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

    private final class RegionContext implements TaskContext {

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
            return handle(
                    server.getRegionScheduler().runDelayed(plugin, location, consumer(task), ticks(delay)), false);
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return handle(
                    server.getRegionScheduler()
                            .runAtFixedRate(plugin, location, consumer(task), ticks(initialDelay), ticks(period)),
                    true);
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

    private final class EntityContext implements TaskContext {

        private final Entity entity;

        EntityContext(Entity entity) {
            this.entity = entity;
        }

        @Override
        public void execute(Runnable task) {
            entity.getScheduler().execute(plugin, logged(task), null, 1L);
        }

        @Override
        public TaskHandle runLater(Duration delay, Runnable task) {
            return handle(entity.getScheduler().runDelayed(plugin, consumer(task), null, ticks(delay)), false);
        }

        @Override
        public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
            return handle(
                    entity.getScheduler()
                            .runAtFixedRate(plugin, consumer(task), null, ticks(initialDelay), ticks(period)),
                    true);
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
