package dev.foliate.core.player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Every read and write for one player, queued behind the previous one: a late save can never
 * overwrite a newer one, whatever the size of the storage pool. Thread-safe, lock-free.
 */
final class PlayerQueues {

    /** The last database operation queued for each player; the next one is chained after it. */
    private final Map<UUID, CompletableFuture<?>> queues = new ConcurrentHashMap<>();

    /**
     * Queues database work for a player behind whatever is already queued for them. Without this,
     * two saves of the same player could run on two storage threads and land in either order.
     *
     * @param work starts the database work when the operation leaves the player's queue, that is
     *     once everything queued before it is done; the next operation waits for its future
     */
    <R extends @Nullable Object> CompletableFuture<R> enqueue(UUID id, Supplier<CompletableFuture<R>> work) {
        CompletableFuture<R> result = new CompletableFuture<>();
        // What the queue holds is not the database work itself but a gate that opens once the
        // callbacks of its result have run: a callback that queues more work (the late join does)
        // has then queued it before anyone waiting for the queue is released.
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicReference<CompletableFuture<R>> chained = new AtomicReference<>();
        queues.compute(id, (key, tail) -> {
            CompletableFuture<?> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            chained.set(previous.handle((done, error) -> null).thenCompose(ignored -> work.get()));
            return gate;
        });
        // Registered outside compute(): a future that is already complete would run this at once,
        // and updating the map from inside one of its own updates is not allowed.
        // Its own outcome goes to result, which the callers observe: this future is not one to wait on.
        CompletableFuture<?> _ = chained.get().whenComplete((value, error) -> {
            if (error == null) {
                result.complete(value);
            } else {
                result.completeExceptionally(error.getCause() != null ? error.getCause() : error);
            }
            gate.complete(null);
            // Drop the queue once this is its last operation, or the map would keep one entry per
            // player ever seen. If a later operation chained after this one, the entry is theirs.
            queues.remove(id, gate);
        });
        return result;
    }

    /**
     * Waits until everything queued so far is done, including what the callbacks of that work
     * queued in turn (a value loaded in the background is saved by its callback).
     */
    boolean await(long timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (true) {
                CompletableFuture<?>[] pending = undone();
                if (pending.length == 0) {
                    return true;
                }
                CompletableFuture.allOf(pending)
                        .handle((done, error) -> null)
                        .get(deadline - System.nanoTime(), TimeUnit.NANOSECONDS);
            }
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Runs {@code action} once no player has work left, including what the callbacks of that work
     * queue in turn, as {@link #await} counts it. Never blocks.
     */
    void whenSettled(Runnable action) {
        CompletableFuture<?>[] pending = undone();
        if (pending.length == 0) {
            action.run();
            return;
        }
        CompletableFuture<?> _ = CompletableFuture.allOf(pending)
                .handle((done, error) -> null)
                .thenRun(() -> whenSettled(action));
    }

    /** How many players still have work queued. */
    int size() {
        return queues.size();
    }

    private CompletableFuture<?>[] undone() {
        return queues.values().stream().filter(tail -> !tail.isDone()).toArray(CompletableFuture[]::new);
    }
}
