package dev.foliate.core.player;

import dev.foliate.core.storage.Database;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongFunction;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Keeps a value of type {@code T} for every online player. This is the machinery that any
 * per-player data needs on Folia, written once; a feature gives it the SQL of its table
 * ({@link PlayerRepository}) and what happens at a join and a quit ({@link PlayerLifecycle}).
 *
 * <p>What it guarantees, each in the class that keeps it:
 *
 * <ol>
 *   <li><b>Load before the player exists.</b> The pre-login event runs off the tick threads, so it
 *       can wait for the database. The value is then held until the join ({@link SessionTracker}).
 *       The read goes ahead of the other players' saves: someone is waiting for it. A player whose
 *       last save has not started yet (they just quit, or the database is down) starts from that
 *       save's value, with no read at all.
 *   <li><b>Read from the cache only.</b> Events, commands and placeholders run on many threads and
 *       none of them may block: {@link #find} reads an immutable value from a concurrent map.
 *   <li><b>Write behind, in order.</b> A change replaces the cached value at once, and its save
 *       is queued behind the player's previous reads and saves ({@link SaveQueue},
 *       {@link PlayerQueues}).
 *   <li><b>Write the latest value only.</b> Changes made while a save waits for a storage thread
 *       join it instead of queuing saves of their own ({@link SaveQueue}).
 *   <li><b>Retry what failed.</b> A save that fails for a reason known to pass is tried again
 *       until it succeeds, any other database error {@value #UNCERTAIN_RETRIES} more times;
 *       anything else is given up at once ({@link SaveQueue}).
 *   <li><b>Never invent data.</b> When the stored value is not known yet, wait for it: saving a
 *       fresh one would erase the real one. A player who joined without a pre-login has no value
 *       until it is loaded.
 *   <li><b>Flush on shutdown.</b> The last saves are waited for before the database closes, and
 *       the players whose value could not be saved in time are reported in one line
 *       ({@link SaveQueue}).
 * </ol>
 *
 * <p>This class keeps the public API, the events, and the flows of a session: login, join, change,
 * quit and shutdown. They run under one lock, {@link #sessions}, which {@link SaveQueue} and
 * {@link SessionTracker} are given rather than having locks of their own.
 *
 * <p>A store is a component of a module, not a module: the module calls {@link #enable()} and
 * {@link #disable()} from its own, after checking that the database is running.
 */
public final class PlayerStore<T extends PlayerData> implements Listener {

    private static final long LOAD_TIMEOUT_SECONDS = 10;
    private static final long FLUSH_TIMEOUT_SECONDS = 15;

    /** How many times an error that may or may not pass is tried again before giving up. */
    static final int UNCERTAIN_RETRIES = Retries.UNCERTAIN_RETRIES;

    /** How long a value loaded at pre-login waits for its join. For tests. */
    static final long LOGIN_TTL_MILLIS = SessionTracker.LOGIN_TTL_MILLIS;

    private final String subject;
    private final Plugin plugin;
    private final Database database;
    private final PlayerRepository<T> repository;
    private final PlayerLifecycle<T> lifecycle;
    private final Supplier<Component> loadFailed;
    private final Logger logger;
    private final Clock clock;

    /** Values of the players in game. */
    private final Map<UUID, T> online = new ConcurrentHashMap<>();

    /**
     * Makes "the player quit" and "their late value is cached" atomic: whichever comes second must
     * see the other, or a value would be cached for a player who is gone. {@link #update} and the
     * join take it too, so that saves are queued in the order the changes were made, and so does
     * {@link #disable()}: once it holds it, nothing is cached or queued any more. The save queue
     * and the session tracker are used under it, and the save queue takes it too when a failed
     * save closes or reopens the save that a write or a login may be looking at.
     */
    private final Object sessions = new Object();

    private final AtomicBoolean enabled = new AtomicBoolean();

    /** The pending logins, recent quits and late joins. Used under {@link #sessions}. */
    private final SessionTracker<T> tracker;

    /** How long the shutdown waits for the saves before it leaves them to the database. */
    private volatile long flushTimeoutSeconds = FLUSH_TIMEOUT_SECONDS;

    /** When the failed saves and late loads are tried again. */
    private final Retries retries = new Retries();

    /** Each player's reads and saves: in order, coalesced, retried, flushed at shutdown. */
    private final SaveQueue<T> saves;

    /**
     * @param subject what the values are, in singular, for the logs ("profile")
     * @param loadFailed what a player is told when their data could not be loaded and they are
     *     refused: letting them in without it would mean guessing it
     */
    public PlayerStore(
            String subject,
            Plugin plugin,
            Database database,
            PlayerRepository<T> repository,
            PlayerLifecycle<T> lifecycle,
            Supplier<Component> loadFailed,
            Logger logger,
            Clock clock) {
        this.subject = subject;
        this.plugin = plugin;
        this.database = database;
        this.repository = repository;
        this.lifecycle = lifecycle;
        this.loadFailed = loadFailed;
        this.logger = logger;
        this.clock = clock;
        this.tracker = new SessionTracker<>(sessions, clock);
        // A save the database refuses: a login may have started from its value. Its join loads
        // what is stored instead; a session that already joined keeps it.
        this.saves = new SaveQueue<>(subject, sessions, database, repository, retries, tracker::forgetLogin, logger);
    }

    /**
     * Starts listening to the logins, joins and quits, and loads the players already online.
     *
     * @throws IllegalStateException when it is already enabled: a second registration would count
     *     every join twice, and one store belongs to one module
     */
    public void enable() {
        if (!enabled.compareAndSet(false, true)) {
            throw new IllegalStateException("The " + subject + " store is already enabled");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        // Players already online when the store starts (the plugin enabled by Paper's /reload or a
        // plugin manager) will see no login nor join: their value is loaded as for a join that
        // came without a pre-login, and their session counts from now.
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            join(player.getUniqueId(), player.getName());
        }
    }

    /**
     * Stops listening, saves the players still online and waits for the saves, 15 seconds at most.
     * Call it before the database closes: modules are disabled in reverse registration order. The
     * storage threads still accept work at this point, unlike the server's schedulers. What is not
     * saved by then is left to the database's own grace period, and the players whose value is
     * lost are reported in one line once every save has ended, as the database stops.
     */
    public void disable() {
        HandlerList.unregisterAll(this);
        long now = clock.millis();
        synchronized (sessions) {
            enabled.set(false);
            online.forEach((id, value) -> saves.write(id, applyLeft(id, value, now)));
            online.clear();
            tracker.clear();
        }
        saves.flush(flushTimeoutSeconds);
    }

    /** The value of an online player. Safe from any thread, never blocks. */
    public Optional<T> find(UUID id) {
        return Optional.ofNullable(online.get(id));
    }

    /**
     * Changes the value of an online player, and saves it. Safe from any thread, never blocks.
     *
     * <p>Changing a value is a read, a computation and a write, and two threads doing it at once
     * must not lose either change: the new value is computed from the one that is cached at that
     * moment, under the lock the join and the quit use, and its save is queued under the same
     * lock, so the saves reach the database in the order the changes were made. {@code change}
     * runs under that lock, so keep it quick and free of side effects: it must not block, and must
     * not call {@code update} again (the inner change would be overwritten).
     *
     * @param change gives the new value from the current one
     * @return the new value, or empty when the player is not online (nothing is changed or saved)
     * @throws IllegalArgumentException when the change returns the value of another player
     */
    public Optional<T> update(UUID id, UnaryOperator<T> change) {
        synchronized (sessions) {
            T current = online.get(id);
            if (current == null) {
                return Optional.empty();
            }
            T changed = Objects.requireNonNull(change.apply(current), "the change gave no " + subject);
            if (!changed.id().equals(id)) {
                throw new IllegalArgumentException("a change must keep the id of the " + subject + " it changes");
            }
            online.put(id, changed);
            saves.write(id, changed);
            return Optional.of(changed);
        }
    }

    /**
     * What is stored in the database for a player, whatever is cached. Blocks on the calling
     * thread: for the tests and the server test, after {@link #awaitWrites}, never on a tick thread.
     */
    Optional<T> stored(UUID id) throws SQLException {
        return database.call(connection -> repository.find(connection, id));
    }

    /** What the values are, in singular ("profile"), for the server test's messages. */
    String subject() {
        return subject;
    }

    /**
     * Waits until everything queued so far has reached the database, including what the callbacks
     * of that work queued in turn (a value loaded in the background is saved by its callback).
     */
    public boolean awaitWrites(long timeoutSeconds) {
        return saves.awaitWrites(timeoutSeconds);
    }

    /**
     * HIGHEST, not MONITOR: this may refuse the login, and a MONITOR listener must never change
     * an event's outcome. It runs after the other plugins' decisions at lower priorities, so data
     * is mostly loaded only for logins that are allowed; a login refused after us anyway leaves a
     * pending entry that the next login sweeps once it has expired. The event cannot be cancelled,
     * so there is no {@code ignoreCancelled}.
     */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            beginLogin(event.getUniqueId(), event.getName());
        } catch (ExecutionException | TimeoutException | RuntimeException e) {
            Throwable cause = e instanceof ExecutionException && e.getCause() != null ? e.getCause() : e;
            if (cause instanceof SQLException || cause instanceof TimeoutException) {
                // The database is down or late: one line each, an outage refuses many players.
                logger.warn(
                        "Refused {}: could not load their {} ({})",
                        event.getName(),
                        subject,
                        Retries.describe(cause));
            } else {
                logger.error("Could not load the {} of {}", subject, event.getName(), e);
            }
            // Letting the player in without their data would mean guessing it.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, loadFailed.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            // The data is not loaded either: the same reason to refuse.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, loadFailed.get());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        join(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        quit(event.getPlayer().getUniqueId());
    }

    /** Loads the value of a player who is logging in. Blocks: never call from a tick thread. */
    void beginLogin(UUID id, String name) throws ExecutionException, TimeoutException, InterruptedException {
        // Someone logging in while already online (the old session is about to be kicked) has a
        // fresher value in memory than in the database. Taken and recorded under the lock the quit
        // uses: a quit in between would otherwise not see this pending login, and the new session
        // would start from a copy older than what the quit saved.
        long readStarted;
        synchronized (sessions) {
            if (!enabled.get()) {
                return;
            }
            T current = online.get(id);
            if (current != null) {
                tracker.loggedIn(id, current);
                return;
            }
            // A save of this player that has not started holds what the database will contain
            // once it lands: no need to wait for it behind the other players' saves, or for its
            // retries while the database is down.
            Optional<T> unsaved = saves.unsaved(id);
            if (unsaved.isPresent()) {
                tracker.loggedIn(id, unsaved.get());
                return;
            }
            readStarted = tracker.readStarts();
        }
        // Not online: wait for the database, without holding the lock.
        T loaded = saves.read(id).get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseGet(() -> keepingId(id, lifecycle.create(id, name, clock.millis()), "create"));
        synchronized (sessions) {
            if (!enabled.get()) {
                return;
            }
            // A session that joined while we were reading is fresher than what we read, and so is
            // one that joined, played and quit meanwhile: our read was queued before its save.
            T current = online.get(id);
            tracker.loggedIn(id, current != null ? current : tracker.leftSince(id, readStarted).orElse(loaded));
        }
    }

    /** How many logins wait for their join. For tests. */
    int pendingLogins() {
        return tracker.pendingLogins();
    }

    void join(UUID id, String name) {
        long now = clock.millis();
        // Under the lock `update` and the quit use, from taking the pending login to queuing the
        // save: a change or a quit in between would otherwise be lost, or saved before this older
        // value and then overwritten by it.
        synchronized (sessions) {
            if (!enabled.get()) {
                return;
            }
            Optional<T> pending = tracker.takeLogin(id, now);
            if (pending.isPresent()) {
                // The server disconnects an old session before the new one joins, and its quit hands
                // over what it saved. Should a join ever come first, starting from the old
                // session's value at least loses none of its changes (its quit would then take
                // this session's value out of the cache: the store tells sessions apart by id only).
                T current = online.get(id);
                T joined = applyJoined(id, current != null ? current : pending.get(), name, now);
                online.put(id, joined);
                saves.write(id, joined);
                return;
            }
            if (online.containsKey(id) || tracker.loadingLate(id)) {
                // Already loaded, or loading, by the store's start: a player who was joining as it
                // started is seen there and again by the join event. One join, not two.
                return;
            }
            // No usable pre-login: the plugin was enabled while the player connected, or the login
            // took longer than anyone should wait. Load without blocking the tick; until then they
            // simply have no value. Known limit: a second login of the same player while this load
            // is still running reads the database before this join is saved, so the new session
            // does not count this join. It needs a re-login within the moment the load takes,
            // right after the plugin was enabled; it loses that one join, nothing else.
            loadLate(id, name, now, tracker.startLateJoin(id), 0);
        }
    }

    /**
     * Loads the value of a player who joined without a pre-login, and tries again while they are
     * online if it fails: they have no value until it is loaded.
     */
    private void loadLate(UUID id, String name, long joinedAt, SessionTracker.LateJoin load, int failures) {
        // Queued under the lock, so that a disable() cannot run in between and find nothing to
        // wait for; a retry that comes after it gives up.
        synchronized (sessions) {
            if (!enabled.get() || !tracker.stillLoading(id, load)) {
                // Disabled, or a retry for a player who quit since: what a new session of theirs
                // saves must not be overwritten by a value read now.
                tracker.endLateJoin(id, load);
                return;
            }
            CompletableFuture<?> _ = saves.read(id).thenAccept(stored -> {
                        T joined = applyJoined(
                                id,
                                stored.orElseGet(() -> keepingId(id, lifecycle.create(id, name, joinedAt), "create")),
                                name,
                                joinedAt);
                        // Queued under the same lock as the quit's save, so the two reach the
                        // database in the order the player's session went.
                        synchronized (sessions) {
                            if (tracker.endLateJoin(id, load)) {
                                online.put(id, joined);
                                saves.write(id, joined);
                            } else {
                                // Gone while the value was loading (the quit recorded when), or
                                // the store was disabled meanwhile: saved as the others were.
                                saves.write(id, applyLeft(id, joined, load.leftAt().orElse(clock.millis())));
                            }
                        }
                    })
                    .exceptionally(error -> {
                        Throwable cause = Retries.unwrap(error);
                        boolean stillWaiting;
                        synchronized (sessions) {
                            stillWaiting = enabled.get() && tracker.stillLoading(id, load);
                            if (!stillWaiting) {
                                tracker.endLateJoin(id, load);
                            }
                        }
                        if (stillWaiting && Retries.worthRetrying(cause, failures)) {
                            if (failures == 0) {
                                logger.warn(
                                        "Could not load the {} of {}, retrying: {}",
                                        subject,
                                        name,
                                        Retries.describe(cause));
                            }
                            retries.after(failures, () -> loadLate(id, name, joinedAt, load, failures + 1));
                        } else if (stillWaiting) {
                            synchronized (sessions) {
                                tracker.endLateJoin(id, load);
                            }
                            logger.error("Could not load the {} of {}", subject, name, cause);
                        }
                        return null;
                    });
        }
    }

    void quit(UUID id) {
        long now = clock.millis();
        synchronized (sessions) {
            tracker.quitWhileLoading(id, now);
            T value = online.remove(id);
            if (value != null) {
                T saved = applyLeft(id, value, now);
                saves.write(id, saved);
                tracker.left(id, saved, now);
            }
        }
    }

    /** How many players had a save given up. For tests. */
    int lostCount() {
        return saves.lostCount();
    }

    /** How many players still have database work queued. For tests: it must fall back to zero. */
    int pendingQueues() {
        return saves.pendingQueues();
    }

    /** Shortens the shutdown's wait, for the tests. */
    void flushTimeoutSeconds(long seconds) {
        flushTimeoutSeconds = seconds;
    }

    /** Makes the retries run at once, or when a test says. */
    void retryDelays(LongFunction<Executor> delays) {
        retries.delays(delays);
    }

    /**
     * What the feature's lifecycle made of a join. A lifecycle that throws or returns another
     * player's value must not cost the player their data: it is logged, and the value stays as it
     * was.
     */
    private T applyJoined(UUID id, T stored, String name, long now) {
        try {
            return keepingId(id, lifecycle.joined(stored, name, now), "joined");
        } catch (RuntimeException e) {
            logger.error("Could not apply the join to the {} of {}: keeping it as it is", subject, name, e);
            return stored;
        }
    }

    /** What the feature's lifecycle made of a quit, with the same fallback as {@link #applyJoined}. */
    private T applyLeft(UUID id, T value, long now) {
        try {
            return keepingId(id, lifecycle.left(value, now), "left");
        } catch (RuntimeException e) {
            logger.error("Could not apply the quit to the {} of {}: saving it as it is", subject, id, e);
            return value;
        }
    }

    /** A value must belong to the player whose queue and row it goes to. */
    private T keepingId(UUID id, T value, String step) {
        Objects.requireNonNull(value, "the lifecycle gave no " + subject + " in " + step);
        if (!value.id().equals(id)) {
            throw new IllegalStateException("The " + subject + " lifecycle changed the id in " + step);
        }
        return value;
    }

    /** Whether the store is enabled. For tests that wait for the shutdown to begin. */
    boolean enabled() {
        return enabled.get();
    }
}
