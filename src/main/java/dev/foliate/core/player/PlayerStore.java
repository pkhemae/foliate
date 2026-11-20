package dev.foliate.core.player;

import dev.foliate.core.storage.Database;
import dev.foliate.core.storage.SqlFunction;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
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
 * <ol>
 *   <li><b>Load before the player exists.</b> The pre-login event runs off the tick threads, so it
 *       can wait for the database. The value is then held until the join.
 *   <li><b>Read from the cache only.</b> Events, commands and placeholders run on many threads and
 *       none of them may block: {@link #find} reads an immutable value from a concurrent map.
 *   <li><b>Write behind, in order.</b> A change replaces the cached value at once and is saved on
 *       a storage thread. Every read and write for one player is queued behind the previous one,
 *       so a late save can never overwrite a newer one, whatever the size of the pool.
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

    /** Players whose value is being loaded after their join, because no pre-login was seen. */
    private final Set<UUID> pendingJoins = ConcurrentHashMap.newKeySet();

    /**
     * Makes "the player quit" and "their late value is cached" atomic: whichever comes second must
     * see the other, or a value would be cached for a player who is gone. {@link #update} and the
     * join take it too, so that saves are queued in the order the changes were made.
     */
    private final Object sessions = new Object();

    /** The last database operation queued for each player; the next one is chained after it. */
    private final Map<UUID, CompletableFuture<?>> queues = new ConcurrentHashMap<>();

    /**
     * @param subject what the values are, in singular, for the logs and the checks ("profile")
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

    /** Starts listening to the logins, joins and quits. */
    public void enable() {
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
            online.values().forEach(value -> write(lifecycle.left(value, now)));
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
            write(changed);
            return Optional.of(changed);
        }
    }

    /**
     * What is stored in the database for a player, whatever is cached. Blocks on the calling
     * thread: for tests and for checks, after {@link #awaitWrites}, never on a tick thread.
     */
    public Optional<T> stored(UUID id) throws SQLException {
        return database.call(connection -> repository.find(connection, id));
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
     * For a check of what the store did for a connected player (see {@code PlayerCheck}): is the
     * value loaded, and has it reached the database?
     *
     * @return what is wrong, naming the player, or null
     */
    public @Nullable String problemWith(Player player) {
        UUID id = player.getUniqueId();
        if (find(id).isEmpty()) {
            return "the " + subject + " of " + player.getName() + " is not loaded";
        }
        if (!awaitWrites(FLUSH_TIMEOUT_SECONDS)) {
            return "the " + subject + " of " + player.getName() + " was still being saved after "
                    + FLUSH_TIMEOUT_SECONDS + " seconds";
        }
        try {
            if (stored(id).isEmpty()) {
                return "the " + subject + " of " + player.getName() + " was not saved";
            }
        } catch (SQLException e) {
            return "the " + subject + " of " + player.getName() + " could not be read back: " + e.getMessage();
        }
        return null;
    }

    /** For a check after the player left: nothing of them stays in memory. */
    public @Nullable String problemAfterQuit(UUID id, String name) {
        return find(id).isPresent() ? "the " + subject + " of " + name + " stayed in memory after the player left" : null;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            beginLogin(event.getUniqueId(), event.getName());
        } catch (ExecutionException | TimeoutException e) {
            logger.error("Could not load the {} of {}", subject, event.getName(), e);
            // Letting the player in without their data would mean guessing it.
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, loadFailed.get());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
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
        // fresher value in memory than in the database.
        T current = online.get(id);
        T value = current != null
                ? current
                : read(id).get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS).orElseGet(() -> lifecycle.create(id, name, clock.millis()));
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
        PendingLogin<T> pending = loggingIn.remove(id);
        if (pending != null && now - pending.at() <= LOGIN_TTL_MILLIS) {
            T joined = lifecycle.joined(pending.value(), name, now);
            // Under the lock `update` uses: a change made between the two lines would otherwise be
            // saved before this older value, and the database would end up with the older one.
            synchronized (sessions) {
                online.put(id, joined);
                write(joined);
            }
            return;
        }
        // No usable pre-login: the plugin was enabled while the player connected, or the login
        // took longer than anyone should wait. Load without blocking the tick; until then they
        // simply have no value.
        pendingJoins.add(id);
        read(id).thenAccept(stored -> {
                    T joined = lifecycle.joined(stored.orElseGet(() -> lifecycle.create(id, name, now)), name, now);
                    synchronized (sessions) {
                        // Gone while the value was loading: nobody to cache it for.
                        if (pendingJoins.remove(id)) {
                            online.put(id, joined);
                        }
                        // Queued under the same lock as the quit's save, so the two reach the
                        // database in the order the player's session went.
                        write(joined);
                    }
                })
                .exceptionally(error -> {
                    logger.error("Could not load the {} of {}", subject, name, error);
                    return null;
                });
    }

    void quit(UUID id) {
        synchronized (sessions) {
            pendingJoins.remove(id);
            T value = online.remove(id);
            if (value != null) {
                write(lifecycle.left(value, clock.millis()));
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

    private void write(T value) {
        enqueue(value.id(), connection -> {
                    repository.save(connection, value);
                    return null;
                })
                .exceptionally(error -> {
                    logger.error("Could not save the {} of {}", subject, value.id(), error);
                    return null;
                });
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

    /** A value loaded at pre-login, and when. */
    private record PendingLogin<T>(T value, long at) {}
}
