package dev.foliate.modules.profile;

import com.destroystokyo.paper.event.player.PlayerConnectionCloseEvent;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleSkippedException;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.storage.Database;
import dev.foliate.core.storage.SqlFunction;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
 * Keeps a {@link PlayerProfile} for every online player. This is the pattern to copy for any
 * per-player data on Folia:
 *
 * <ol>
 *   <li><b>Load before the player exists.</b> The pre-login event runs off the tick threads, so it
 *       can wait for the database. The profile is then held until the join.
 *   <li><b>Read from the cache only.</b> Events, commands and placeholders run on many threads and
 *       none of them may block: they read an immutable profile from a concurrent map.
 *   <li><b>Write behind, in order.</b> A change replaces the cached profile at once and is saved
 *       on a storage thread. Every read and write for one player is queued behind the previous
 *       one, so a late save can never overwrite a newer one, whatever the size of the pool.
 *   <li><b>Never invent data.</b> When the stored profile is not known yet, wait for it: saving a
 *       fresh one would erase the real one.
 *   <li><b>Flush on shutdown.</b> The last saves are queued like the others, then waited for,
 *       before the database closes.
 * </ol>
 */
public final class ProfileModule implements Module, Listener {

    private static final long LOAD_TIMEOUT_SECONDS = 10;
    private static final long FLUSH_TIMEOUT_SECONDS = 15;

    private final Plugin plugin;
    private final Database database;
    private final MessageService messages;
    private final PlaceholderRegistry placeholders;
    private final Logger logger;
    private final Clock clock;
    private final ProfileRepository repository = new ProfileRepository();

    /** Profiles of the players in game. */
    private final Map<UUID, PlayerProfile> online = new ConcurrentHashMap<>();

    /** Profiles loaded at pre-login, waiting for a join that may never come. */
    private final Map<UUID, PlayerProfile> loggingIn = new ConcurrentHashMap<>();

    /** The last database operation queued for each player; the next one is chained after it. */
    private final Map<UUID, CompletableFuture<?>> queues = new ConcurrentHashMap<>();

    public ProfileModule(
            Plugin plugin,
            Database database,
            MessageService messages,
            PlaceholderRegistry placeholders,
            Logger logger,
            Clock clock) {
        this.plugin = plugin;
        this.database = database;
        this.messages = messages;
        this.placeholders = placeholders;
        this.logger = logger;
        this.clock = clock;
    }

    @Override
    public String name() {
        return "profiles";
    }

    @Override
    public void enable() throws ModuleSkippedException {
        if (!database.isRunning()) {
            throw new ModuleSkippedException("it needs the database, which is not running");
        }
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        placeholders.register("joins", player -> player == null
                ? null
                : find(player.getUniqueId()).map(profile -> String.valueOf(profile.joins())).orElse(null));
    }

    /**
     * Runs before the database closes: modules are disabled in reverse registration order. The
     * storage threads still accept work at this point, unlike the server's schedulers.
     */
    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        placeholders.unregister("joins");
        long now = clock.millis();
        online.values().forEach(profile -> write(profile.seen(now)));
        online.clear();
        loggingIn.clear();
        if (!awaitWrites(FLUSH_TIMEOUT_SECONDS)) {
            logger.warn("Some player profiles were not saved within {} seconds", FLUSH_TIMEOUT_SECONDS);
        }
    }

    /** The profile of an online player. Safe from any thread, never blocks. */
    public Optional<PlayerProfile> find(UUID id) {
        return Optional.ofNullable(online.get(id));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        if (event.getLoginResult() != AsyncPlayerPreLoginEvent.Result.ALLOWED) {
            return;
        }
        try {
            beginLogin(event.getUniqueId(), event.getName());
        } catch (ExecutionException | TimeoutException e) {
            logger.error("Could not load the profile of {}", event.getName(), e);
            // Letting the player in without their profile would mean guessing it.
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER, messages.render(Locale.ROOT, "profile.load-failed"));
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

    /**
     * A connection can end after pre-login without ever becoming a join (banned, server full,
     * dropped). No quit event follows, so this is where its profile is let go.
     */
    @EventHandler
    public void onConnectionClose(PlayerConnectionCloseEvent event) {
        abandonLogin(event.getPlayerUniqueId());
    }

    /** Loads the profile of a player who is logging in. Blocks: never call from a tick thread. */
    void beginLogin(UUID id, String name) throws ExecutionException, TimeoutException, InterruptedException {
        // Someone logging in while already online (the old session is about to be kicked) has a
        // fresher profile in memory than in the database.
        PlayerProfile current = online.get(id);
        PlayerProfile profile = current != null
                ? current
                : read(id)
                        .get(LOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                        .orElseGet(() -> PlayerProfile.create(id, name, clock.millis()));
        loggingIn.put(id, profile);
    }

    void abandonLogin(UUID id) {
        loggingIn.remove(id);
    }

    void join(UUID id, String name) {
        long now = clock.millis();
        PlayerProfile loaded = loggingIn.remove(id);
        if (loaded != null) {
            PlayerProfile joined = loaded.joined(name, now);
            online.put(id, joined);
            write(joined);
            return;
        }
        // No pre-login was seen for this player, which happens when the plugin is enabled while
        // they connect. Load without blocking the tick; until then they simply have no profile.
        read(id).thenAccept(stored -> {
                    PlayerProfile joined = stored.orElseGet(() -> PlayerProfile.create(id, name, now))
                            .joined(name, now);
                    if (plugin.getServer().getPlayer(id) != null) {
                        online.put(id, joined);
                    }
                    write(joined);
                })
                .exceptionally(error -> {
                    logger.error("Could not load the profile of {}", name, error);
                    return null;
                });
    }

    void quit(UUID id) {
        PlayerProfile profile = online.remove(id);
        if (profile != null) {
            write(profile.seen(clock.millis()));
        }
    }

    /** Waits until everything queued so far has reached the database. */
    boolean awaitWrites(long timeoutSeconds) {
        try {
            CompletableFuture.allOf(queues.values().toArray(CompletableFuture[]::new))
                    .handle((done, error) -> null)
                    .get(timeoutSeconds, TimeUnit.SECONDS);
            return true;
        } catch (TimeoutException | ExecutionException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private CompletableFuture<Optional<PlayerProfile>> read(UUID id) {
        return enqueue(id, connection -> repository.find(connection, id));
    }

    private void write(PlayerProfile profile) {
        enqueue(profile.id(), connection -> {
                    repository.save(connection, profile);
                    return null;
                })
                .exceptionally(error -> {
                    logger.error("Could not save the profile of {}", profile.name(), error);
                    return null;
                });
    }

    /**
     * Queues database work for a player behind whatever is already queued for them. Without this,
     * two saves of the same player could run on two storage threads and land in either order.
     */
    private <T> CompletableFuture<T> enqueue(UUID id, SqlFunction<T> work) {
        CompletableFuture<T> result = new CompletableFuture<>();
        queues.compute(id, (key, tail) -> {
            CompletableFuture<?> previous = tail == null ? CompletableFuture.completedFuture(null) : tail;
            return previous.handle((done, error) -> null)
                    .thenCompose(ignored -> database.submit(work))
                    .whenComplete((value, error) -> {
                        if (error == null) {
                            result.complete(value);
                        } else {
                            result.completeExceptionally(error.getCause() != null ? error.getCause() : error);
                        }
                    });
        });
        // Drop the queue once it is idle, or the map would keep one entry per player ever seen.
        result.whenComplete((value, error) -> queues.computeIfPresent(id, (key, tail) -> tail.isDone() ? null : tail));
        return result;
    }
}
