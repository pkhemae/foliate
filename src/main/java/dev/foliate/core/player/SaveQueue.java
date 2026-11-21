package dev.foliate.core.player;

import dev.foliate.core.storage.Database;
import dev.foliate.core.storage.SqlErrors;
import java.sql.SQLException;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The database side of a {@link PlayerStore}: each player's reads and saves, in order, with the
 * saves coalesced, retried and, at shutdown, flushed and reported.
 *
 * <ol start="3">
 *   <li><b>Write behind, in order.</b> A change replaces the cached value at once and is saved on
 *       a storage thread. Every read and write for one player is queued behind the previous one
 *       ({@link PlayerQueues}), so a late save can never overwrite a newer one, whatever the size
 *       of the pool.
 *   <li><b>Write the latest value only.</b> A save takes the value as it is when it starts on a
 *       storage thread: changes made while it waits join it instead of queuing saves of their own
 *       ({@link PendingSave}). A thousand changes in a row are one write, not a thousand, and a
 *       login waits for that one at most. A read queued behind a waiting save may therefore see a
 *       change made after the read was queued: never an older one.
 *   <li><b>Retry what failed.</b> A save that fails for a reason known to pass (the database is
 *       unreachable, busy or timed out) keeps the player's latest value and is tried again, ever
 *       less often, until it succeeds ({@link Retries}). The player's later reads and saves wait
 *       behind it. An outage is logged when the first player's saves start failing and when the
 *       last one's succeed, not once per save ({@link OutageLog}). Any other database error is
 *       tried {@value Retries#UNCERTAIN_RETRIES} more times, then logged with its stack trace and
 *       given up; anything else (an error of the repository's own code) is logged and given up at
 *       once: a save that can never succeed (a broken constraint) must not hold the player's
 *       queue, and their logins, forever.
 * </ol>
 *
 * <ol start="7">
 *   <li><b>Flush on shutdown.</b> The last saves are queued like the others, then waited for,
 *       before the database closes. The players whose value could not be saved in time are
 *       reported in one line.
 * </ol>
 *
 * <p>Thread-safe. {@link #write} and {@link #unsaved} are called under the store's lock, the one
 * given to the constructor, which also orders them with what a failed save does: they read a
 * player's waiting save and act on it, and the failed save must not close or reopen it in between.
 */
final class SaveQueue<T extends PlayerData> {

    /** How many players a report of unsaved values names; the others are counted. */
    private static final int REPORTED_IDS = 10;

    private final String subject;
    private final Object lock;
    private final Database database;
    private final PlayerRepository<T> repository;
    private final Retries retries;
    private final Consumer<UUID> refused;
    private final Logger logger;

    /** Each player's reads and saves, one after the other. */
    private final PlayerQueues queues = new PlayerQueues();

    /** Logs when the saves start failing, and when they land again. */
    private final OutageLog outage;

    /**
     * Each player's last queued save, until it starts: a newer value replaces the one it will write
     * instead of queuing another save.
     */
    private final Map<UUID, PendingSave<T>> waitingSaves = new ConcurrentHashMap<>();

    /** The delay before each player's next retry, so that the shutdown can cut it short. */
    private final Map<UUID, CompletableFuture<@Nullable Void>> retryWaits = new ConcurrentHashMap<>();

    /**
     * Players whose latest value could not be saved because the database stopped or the shutdown
     * stopped waiting, for the shutdown's report. A later save that lands takes them out.
     */
    private final Set<UUID> lost = ConcurrentHashMap.newKeySet();

    /**
     * Set when the shutdown stops waiting: a save waiting for its retry tries once more at once,
     * and a save that fails from then on gives up.
     */
    private volatile boolean abandoned;

    /**
     * @param subject what the values are, in singular, for the logs ("profile")
     * @param lock the store's lock, under which {@link #write} and {@link #unsaved} are called
     * @param retries when a failed save is tried again
     * @param refused told, under {@code lock}, of a player whose save is given up because the
     *     database refuses it: a login may have started from that value
     */
    SaveQueue(
            String subject,
            Object lock,
            Database database,
            PlayerRepository<T> repository,
            Retries retries,
            Consumer<UUID> refused,
            Logger logger) {
        this.subject = subject;
        this.lock = lock;
        this.database = database;
        this.repository = repository;
        this.retries = retries;
        this.refused = refused;
        this.logger = logger;
        this.outage = new OutageLog(subject, logger);
    }

    /** Reads a player's stored value, ahead of the other players' saves but after their own. */
    CompletableFuture<Optional<T>> read(UUID id) {
        return queues.enqueue(id, () -> database.submitFirst(connection -> repository.find(connection, id)));
    }

    /**
     * Saves a value under the player's own queue: {@code id} is the key, whatever the value says.
     * When a save of this player is queued and has not started, the value replaces the one it will
     * write, and nothing more is queued: that save writes the latest value when it starts, still
     * after everything queued before it. Called under the store's lock.
     */
    void write(UUID id, T value) {
        assert Thread.holdsLock(lock);
        // Called under the lock, so no other write of this player runs between the two lines.
        PendingSave<T> waiting = waitingSaves.get(id);
        if (waiting != null && waiting.replace(value)) {
            return;
        }
        PendingSave<T> save = new PendingSave<>(value);
        waitingSaves.put(id, save);
        CompletableFuture<?> _ = queues.enqueue(id, () -> persist(id, save, 0)).exceptionally(error -> {
            logger.error("Could not save the {} of {}", subject, id, error);
            return null;
        });
    }

    /**
     * The value a save of this player that has not started will write: what the database will
     * contain once it lands. Empty when there is none, or when the database may refuse it. Called
     * under the store's lock.
     */
    Optional<T> unsaved(UUID id) {
        assert Thread.holdsLock(lock);
        PendingSave<T> waiting = waitingSaves.get(id);
        return waiting == null ? Optional.empty() : waiting.peek();
    }

    /**
     * Waits until everything queued so far has reached the database, including what the callbacks
     * of that work queued in turn (a value loaded in the background is saved by its callback).
     */
    boolean awaitWrites(long timeoutSeconds) {
        return queues.await(timeoutSeconds);
    }

    /**
     * Waits for the saves queued so far, {@code timeoutSeconds} at most, then reports the players
     * whose value is lost: at once when every save has ended, otherwise once they all do.
     */
    void flush(long timeoutSeconds) {
        if (awaitWrites(timeoutSeconds)) {
            reportLost();
            return;
        }
        // The saves waiting for a retry stop waiting and try once more; what is still queued or
        // running gets the database's own grace period, which comes next in the shutdown. The
        // report waits for the outcome: the database completes every operation as it stops, so
        // the report is written then, with the players actually lost.
        abandoned = true;
        retryWaits.values().forEach(wait -> wait.complete(null));
        queues.whenSettled(this::reportLost);
    }

    /** How many players had a save given up. For tests. */
    int lostCount() {
        return lost.size();
    }

    /** How many players still have database work queued. For tests: it must fall back to zero. */
    int pendingQueues() {
        return queues.size();
    }

    /**
     * Runs a save, and tries it again while it fails for a reason that may pass. The player's queue
     * waits for the outcome: what is queued after it must not overtake it.
     */
    private CompletableFuture<@Nullable Void> persist(UUID id, PendingSave<T> save, int failures) {
        return database.<@Nullable Void>submit(connection -> {
                    // Taken as the save starts, with a thread and a connection: what changed while
                    // it waited for them is part of it. From here on, a change is a save of its own.
                    repository.save(connection, save.start());
                    return null;
                })
                .handle((done, error) -> error)
                .thenCompose(error ->
                        error == null ? saved(id, save) : failed(id, save, failures, Retries.unwrap(error)));
    }

    private CompletableFuture<@Nullable Void> saved(UUID id, PendingSave<T> save) {
        waitingSaves.remove(id, save);
        lost.remove(id);
        outage.retryEnded(id, true);
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<@Nullable Void> failed(UUID id, PendingSave<T> save, int failures, Throwable cause) {
        boolean stopping = abandoned || !database.isRunning();
        if (stopping || !Retries.worthRetrying(cause, failures)) {
            synchronized (lock) {
                // Closed, so that a change made from now on is a save of its own.
                save.start();
                waitingSaves.remove(id, save);
            }
            outage.retryEnded(id, false);
            if (stopping) {
                // Reported with the others when the store stops.
                lost.add(id);
            } else {
                // The same save would fail again: a fault of the repository or of the value.
                logger.error("Could not save the {} of {}: giving up", subject, id, cause);
                synchronized (lock) {
                    // A login may have started from this value, which the database refuses: its
                    // join loads what is stored instead. A session that already joined keeps it.
                    refused.accept(id);
                }
            }
            return CompletableFuture.completedFuture(null);
        }
        synchronized (lock) {
            if (waitingSaves.get(id) != save) {
                // A change made while this save ran is queued behind it, with the whole newer
                // value: that save replaces this one. The player stays counted as retrying until
                // it lands.
                return CompletableFuture.completedFuture(null);
            }
            // The value this save holds is still the latest: it takes changes again until the
            // retry starts. A login does not start from it unless the error is known to pass:
            // the database may be refusing this very value.
            save.reopen(!(cause instanceof SQLException sql && SqlErrors.mayPass(sql)));
        }
        outage.retryStarted(id, cause);
        CompletableFuture<@Nullable Void> wait = new CompletableFuture<>();
        retryWaits.put(id, wait);
        retries.after(failures, () -> wait.complete(null));
        if (abandoned) {
            // The shutdown stopped waiting meanwhile: one last try, at once.
            wait.complete(null);
        }
        return wait.thenCompose(ignored -> {
            retryWaits.remove(id, wait);
            return persist(id, save, failures + 1);
        });
    }

    /** Logs, in one line, the players whose latest value could not be saved. */
    private void reportLost() {
        Set<UUID> unsaved = new TreeSet<>(lost);
        if (!unsaved.isEmpty()) {
            logger.warn(
                    "The {}s of {} could not be saved: their latest changes are probably lost ({}{})",
                    subject,
                    unsaved.size() == 1 ? "1 player" : unsaved.size() + " players",
                    String.join(", ", unsaved.stream().limit(REPORTED_IDS).map(UUID::toString).toList()),
                    unsaved.size() > REPORTED_IDS ? ", ..." : "");
        }
    }
}
