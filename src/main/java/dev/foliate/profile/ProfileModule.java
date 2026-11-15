package dev.foliate.profile;

import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleSkippedException;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.storage.Database;
import java.sql.SQLException;
import java.time.Clock;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.entity.Player;
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
 *       can wait for the database. When it ends, the profile is in the cache.
 *   <li><b>Read from the cache only.</b> Events, commands and placeholders run on many threads and
 *       none of them may block: they read an immutable profile from a concurrent map.
 *   <li><b>Write behind.</b> A change replaces the cached profile at once and is saved on a
 *       storage thread.
 *   <li><b>Flush on shutdown.</b> Nothing can be scheduled then, so the remaining profiles are
 *       saved synchronously before the database closes.
 * </ol>
 */
public final class ProfileModule implements Module, Listener {

    private final Plugin plugin;
    private final Database database;
    private final MessageService messages;
    private final PlaceholderRegistry placeholders;
    private final Logger logger;
    private final Clock clock;
    private final ProfileRepository repository = new ProfileRepository();
    private final Map<UUID, PlayerProfile> online = new ConcurrentHashMap<>();

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

    /** Runs before the database closes: modules are disabled in reverse registration order. */
    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        placeholders.unregister("joins");
        long now = clock.millis();
        for (PlayerProfile profile : online.values()) {
            try {
                PlayerProfile last = profile.seen(now);
                database.call(connection -> {
                    repository.save(connection, last);
                    return null;
                });
            } catch (SQLException e) {
                logger.error("Could not save the profile of {} during shutdown", profile.name(), e);
            }
        }
        online.clear();
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
        UUID id = event.getUniqueId();
        try {
            PlayerProfile profile = database.call(connection -> repository.find(connection, id))
                    .orElseGet(() -> PlayerProfile.create(id, event.getName(), clock.millis()));
            online.put(id, profile);
        } catch (SQLException e) {
            logger.error("Could not load the profile of {}", event.getName(), e);
            // Letting the player in with an empty profile would overwrite the real one on save.
            event.disallow(
                    AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    messages.render(Locale.ROOT, "profile.load-failed"));
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        long now = clock.millis();
        PlayerProfile updated = online.compute(player.getUniqueId(), (id, profile) -> {
            // A profile is always there after pre-login; this only covers a plugin enabled late.
            PlayerProfile current = profile != null ? profile : PlayerProfile.create(id, player.getName(), now);
            return current.joined(player.getName(), now);
        });
        save(updated);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        PlayerProfile profile = online.remove(event.getPlayer().getUniqueId());
        if (profile != null) {
            save(profile.seen(clock.millis()));
        }
    }

    private void save(PlayerProfile profile) {
        database.submit(connection -> {
                    repository.save(connection, profile);
                    return null;
                })
                .exceptionally(error -> {
                    logger.error("Could not save the profile of {}", profile.name(), error);
                    return null;
                });
    }
}
