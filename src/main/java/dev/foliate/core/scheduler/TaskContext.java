package dev.foliate.core.scheduler;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

/**
 * A place where work can run: the global region, the region owning a location, the region owning
 * an entity, or an async thread. Obtained from {@link Schedulers}.
 *
 * <p>To chain work across contexts, use {@link #run} and {@link #supply}: their futures always
 * complete, including when the entity they were bound to disappears.
 *
 * <pre>{@code
 * schedulers.async().supply(() -> repository.load(id))
 *     .thenCompose(data -> schedulers.entity(player).run(() -> apply(player, data)));
 * }</pre>
 *
 * <p>A context is also an {@link Executor}, but passing an entity context to
 * {@code thenApplyAsync} and the like is weaker: if the entity is removed after the task was
 * accepted, the task is dropped and the dependent stage never completes.
 *
 * <p>Delays are rounded down to whole ticks (50 ms) in tick-based contexts, with a minimum of one
 * tick. Negative delays count as zero.
 */
public interface TaskContext extends Executor {

    /**
     * Runs the task as soon as possible in this context. Exceptions are logged.
     *
     * @throws EntityRetiredException in an entity context, when the entity no longer exists
     */
    @Override
    void execute(Runnable task);

    /**
     * Runs the task once after the delay. Exceptions are logged. In an entity context the handle
     * is already cancelled when the entity no longer exists.
     */
    TaskHandle runLater(Duration delay, Runnable task);

    /**
     * Runs the task repeatedly until the handle is cancelled. Exceptions are logged. In an entity
     * context the task stops by itself when the entity is removed.
     */
    TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task);

    /**
     * Whether the calling thread already owns this context and may touch what it protects right
     * now. Always false for the async context.
     */
    boolean isCurrent();

    /**
     * Runs the task immediately when {@link #isCurrent()}, otherwise schedules it with
     * {@link #execute}. Exceptions are logged either way.
     */
    void executeOrRun(Runnable task);

    /**
     * Runs the task in this context and reports the outcome through the future. Exceptions are not
     * logged: they complete the future, and it is up to the caller to handle them.
     */
    default CompletableFuture<Void> run(Runnable task) {
        return supply(() -> {
            task.run();
            return null;
        });
    }

    /**
     * Computes a value in this context. Same exception contract as {@link #run(Runnable)}.
     *
     * <p>In an entity context the future fails with {@link EntityRetiredException} when the entity
     * no longer exists.
     */
    <T> CompletableFuture<T> supply(Supplier<T> supplier);
}
