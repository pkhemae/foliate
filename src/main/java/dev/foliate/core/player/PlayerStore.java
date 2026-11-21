package dev.foliate.core.player;

import dev.foliate.core.storage.Database;
import dev.foliate.core.storage.SqlFunction;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Keeps a value of type {@code T} for every online player. This is the machinery that any
 * per-player data needs on Folia, written once; a feature gives it the SQL of its table
 * ({@link PlayerRepository}) and what happens at a join and a quit ({@link PlayerLifecycle}).
 *
 * <ol>
 *   <li><b>Load before the player exists.</b> The pre-login event runs off the tick threads, so it
 *       can wait for the database. The value is then held until the join.
 *   <li><b>Read from the cache only.</b> Events, commands and placeholders run on many threads and
 *       none of them may block: {@link #find} reads an immutable value from a concurrent map.
 *   <li><b>Write behind, in order.</b> A change replaces the cached value at once and is saved on
 *       a storage thread. Every read and write for one player is queued behind the previous one,
 *       so a late save can never overwrite a newer one, whatever the size of the pool.
 *   <li><b>Write the latest value only.</b> A save that is queued and has not started yet takes
 *       the value as it is when it starts: changes made meanwhile join it instead of queuing saves
 *       of their own. A thousand changes in a row are two writes, not a thousand, and a login
 *       waits for those two at most.
 *   <li><b>Never invent data.</b> When the stored value is not known yet, wait for it: saving a
 *       fresh one would erase the real one.
 *   <li><b>Flush on shutdown.</b> The last saves are queued like the others, then waited for,
 *       before the database closes.
 * </ol>
 *
 * <p>A store is a component of a module, not a module: the module calls {@link #enable()} and
 * {@link #disable()} from its own, after checking that the database is running.
 */
public final class PlayerStore<T extends PlayerData> implements Listener {

    private static final long LOAD_TIMEOUT_SECONDS = 10;
    private static final long FLUSH_TIMEOUT_SECONDS = 15;

    /**
     * How long a value loaded at pre-login waits for its join. A connection can end between the
     * two without a trace (banned, server full, dropped), so an entry older than this is forgotten.
     */
    static final long LOGIN_TTL_MILLIS = 5 * 60_000L;

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

    /** Values loaded at pre-login, waiting for a join that may never come. */
    private final Map<UUID, PendingLogin<T>> loggingIn = new ConcurrentHashMap<>();

    /**
     * The load of each player whose value is being loaded after their join, because no pre-login
     * was seen. A quit takes it out and records its time: the value is then saved as of then.
     */
    private final Map<UUID, LateJoin> pendingJoins = new ConcurrentHashMap<>();

    /**
     * Makes "the player quit" and "their late value is cached" atomic: whichever comes second must
     * see the other, or a value would be cached for a player who is gone. {@link #update} and the
     * join take it too, so that saves are queued in the order the changes were made, and so does
     * {@link #disable()}: once it holds it, nothing is cached or queued any more.
     */
    private final Object sessions = new Object();

    private final AtomicBoolean enabled = new AtomicBoolean();

    /** The last database operation queued for each player; the next one is chained after it. */
    private final Map<UUID, CompletableFuture<?>> queues = new ConcurrentHashMap<>();

    /**
     * Each player's last queued save, until it starts: a newer value replaces the one it will write
     * instead of queuing another save.
     */
    private final Map<UUID, PendingSave<T>> waitingSaves = new ConcurrentHashMap<>();

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
    }

    /**
     * Starts listening to the logins, joins and quits.
     *
     * @throws IllegalStateException when it is already enabled: a second registration would count
     *     every join twice, and one store belongs to one module
     */
    public void enable() {
        if (!enabled.compareAndSet(false, true)) {
            throw new IllegalStateException("The " + subject + " store is already enabled");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Stops listening, saves the players still online and waits for every save. Call it before the
     * database closes: modules are disabled in reverse registration order. The storage threads
     * still accept work at this point, unlike the server's schedulers.
     */
    public void disable() {
        HandlerList.unregisterAll(this);
        long now = clock.millis();
        synchronized (sessions) {
            enabled.set(false);
            online.forEach((id, value) -> write(id, applyLeft(id, value, now)));
            online.clear();
            loggingIn.clear();
            pendingJoins.clear();
        }
        if (!awaitWrites(FLUSH_TIMEOUT_SECONDS)) {
            logger.warn("Some player {}s were not saved within {} seconds", subject, FLUSH_TIMEOUT_SECONDS);
        }
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
            write(id, changed);
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
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        try {
            while (true) {
                CompletableFuture<?>[] pending =
                        queues.values().stream().filter(tail -> !tail.isDone()).toArray(CompletableFuture[]::new);
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
            logger.error("Could not load the {} of {}", subject, event.getName(), e);
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
        synchronized (sessions) {
            if (!enabled.get()) {
                return;
            }
            T current = online.get(id);
            if (current != null) {
                remember(id, current);
                return;
            }
        }
        // Not online: wait for the database, without holding the lock.
        T loaded = read(id).get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .orElseGet(() -> keepingId(id, lifecycle.create(id, name, clock.millis()), "create"));
        synchronized (sessions) {
            if (!enabled.get()) {
                return;
            }
            // A session that joined while we were reading is fresher than what we read.
            T current = online.get(id);
            remember(id, current != null ? current : loaded);
        }
    }

    /** Records a pending login. Called under the lock. */
    private void remember(UUID id, T value) {
        long now = clock.millis();
        // Logins that never became a join leave nothing else to tell us: forget them here.
        loggingIn.values().removeIf(pending -> now - pending.at() > LOGIN_TTL_MILLIS);
        loggingIn.put(id, new PendingLogin<>(value, now));
    }

    /** How many logins wait for their join. For tests. */
    int pendingLogins() {
        return loggingIn.size();
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
            PendingLogin<T> pending = loggingIn.remove(id);
            if (pending != null && now - pending.at() <= LOGIN_TTL_MILLIS) {
                // The server disconnects an old session before the new one joins, and its quit hands
                // over what it saved. Should a join ever come first, starting from the old
                // session's value at least loses none of its changes (its quit would then take
                // this session's value out of the cache: the store tells sessions apart by id only).
                T current = online.get(id);
                T joined = applyJoined(id, current != null ? current : pending.value(), name, now);
                online.put(id, joined);
                write(id, joined);
                return;
            }
            // No usable pre-login: the plugin was enabled while the player connected, or the login
            // took longer than anyone should wait. Load without blocking the tick; until then they
            // simply have no value. Queued under the lock too, so that a disable() cannot run in
            // between and find nothing to wait for. Known limit: a second login of the same player
            // while this load is still running reads the database before this join is saved, so
            // the new session does not count this join. It needs a re-login within the moment the
            // load takes, right after the plugin was enabled; it loses that one join, nothing else.
            LateJoin load = new LateJoin();
            pendingJoins.put(id, load);
            read(id).thenAccept(stored -> {
                        T joined = applyJoined(id, stored.orElseGet(() -> keepingId(id, lifecycle.create(id, name, now), "create")), name, now);
                        // Queued under the same lock as the quit's save, so the two reach the
                        // database in the order the player's session went.
                        synchronized (sessions) {
                            if (pendingJoins.remove(id, load)) {
                                online.put(id, joined);
                                write(id, joined);
                            } else {
                                // Gone while the value was loading (the quit recorded when), or
                                // the store was disabled meanwhile: saved as the others were.
                                write(id, applyLeft(id, joined, load.leftAt.orElse(clock.millis())));
                            }
                        }
                    })
                    .exceptionally(error -> {
                        synchronized (sessions) {
                            pendingJoins.remove(id, load);
                        }
                        logger.error("Could not load the {} of {}", subject, name, error);
                        return null;
                    });
        }
    }

    void quit(UUID id) {
        long now = clock.millis();
        synchronized (sessions) {
            LateJoin loading = pendingJoins.remove(id);
            if (loading != null) {
                loading.leftAt = OptionalLong.of(now);
            }
            T value = online.remove(id);
            if (value != null) {
                T saved = applyLeft(id, value, now);
                write(id, saved);
                // A second login while this session was online took a copy of its value at
                // pre-login, and the new session will start from it. What this session changed
                // since then is in what we just saved: start the new session from that instead.
                loggingIn.computeIfPresent(id, (key, pending) -> new PendingLogin<>(saved, pending.at()));
            }
        }
    }

    /** How many players still have database work queued. For tests: it must fall back to zero. */
    int pendingQueues() {
        return queues.size();
    }

    private CompletableFuture<Optional<T>> read(UUID id) {
        return enqueue(id, connection -> repository.find(connection, id));
    }

    /**
     * Saves a value under the player's own queue: {@code id} is the key, whatever the value says.
     * When a save of this player is already queued and has not started, the value replaces the one
     * it will write, and nothing more is queued: that save writes the latest value when it starts,
     * still after everything queued before it.
     */
    private void write(UUID id, T value) {
        // Called under the lock, so no other write of this player runs between the two lines.
        PendingSave<T> waiting = waitingSaves.get(id);
        if (waiting != null && waiting.replace(value)) {
            return;
        }
        PendingSave<T> save = new PendingSave<>(value);
        waitingSaves.put(id, save);
        enqueue(id, connection -> {
                    // From here on, a change queues a save of its own, behind this one.
                    T latest = save.start();
                    waitingSaves.remove(id, save);
                    repository.save(connection, latest);
                    return null;
                })
                .exceptionally(error -> {
                    waitingSaves.remove(id, save);
                    logger.error("Could not save the {} of {}", subject, id, error);
                    return null;
                });
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

    /**
     * Queues database work for a player behind whatever is already queued for them. Without this,
     * two saves of the same player could run on two storage threads and land in either order.
     */
    private <R> CompletableFuture<R> enqueue(UUID id, SqlFunction<R> work) {
        CompletableFuture<R> result = new CompletableFuture<>();
        // What the queue holds is not the database work itself but a gate that opens once the
        // callbacks of its result have run: a callback that queues more work (the late join does)
        // has then queued it before anyone waiting for the queue is released.
        CompletableFuture<Void> gate = new CompletableFuture<>();
        AtomicReference<CompletableFuture<R>> chained = new AtomicReference<>();
        queues.compute(id, (key, tail) -> {
            CompletableFuture<?> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            chained.set(previous.handle((done, error) -> null).thenCompose(ignored -> database.submit(work)));
            return gate;
        });
        // Registered outside compute(): a future that is already complete would run this at once,
        // and updating the map from inside one of its own updates is not allowed.
        chained.get().whenComplete((value, error) -> {
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

    /** One load after a join; {@code leftAt} is written and read under the store's lock. */
    private static final class LateJoin {
        OptionalLong leftAt = OptionalLong.empty();
    }

    /** Whether the store is enabled. For tests that wait for the shutdown to begin. */
    boolean enabled() {
        return enabled.get();
    }

    /** A queued save, and the value it writes, which can change until it starts. */
    private static final class PendingSave<T> {

        private T value;
        private boolean started;

        PendingSave(T value) {
            this.value = value;
        }

        /** @return false when the save has started: the value must then be saved by another one */
        synchronized boolean replace(T newer) {
            if (started) {
                return false;
            }
            value = newer;
            return true;
        }

        synchronized T start() {
            started = true;
            return value;
        }
    }

    /** A value loaded at pre-login, and when. */
    private record PendingLogin<T>(T value, long at) {}
}
