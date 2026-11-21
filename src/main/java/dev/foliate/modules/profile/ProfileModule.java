package dev.foliate.modules.profile;

import dev.foliate.core.command.Subcommand;
import dev.foliate.core.command.SubcommandProvider;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleSkippedException;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.player.PlayerLifecycle;
import dev.foliate.core.player.PlayerStore;
import dev.foliate.core.storage.Database;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.bukkit.Server;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Keeps a {@link PlayerProfile} for every online player: what the plugin remembers about them
 * between sessions. It is the first use of {@link PlayerStore}, which does the loading, the cache,
 * the ordered saves and the flush on shutdown; what is left here is what belongs to the profile
 * (its fields, its SQL, its placeholder; its check on a real server is ProfileCheck, in the serverTest
 * source set). To keep another kind of data per player,
 * give a store its own repository and lifecycle, as this module does.
 */
public final class ProfileModule implements Module, SubcommandProvider {

    /**
     * The SQL scripts of this module, under {@code migrations/profile/}, in the order they apply:
     * to give to the {@code Database}. To change the table, add a script to that folder and append
     * it here, and nowhere else: the plugin and the tests read this list, and the scripts of other
     * modules do not depend on its length.
     */
    public static final List<String> MIGRATIONS = List.of("profile/001_create_profiles.sql");

    private final Database database;
    private final MessageService messages;
    private final Server server;
    private final PlaceholderRegistry placeholders;
    private final PlayerStore<PlayerProfile> store;
    private final ProfileRepository repository = new ProfileRepository();

    public ProfileModule(
            Plugin plugin,
            Database database,
            MessageService messages,
            PlaceholderRegistry placeholders,
            Logger logger,
            Clock clock) {
        this.database = database;
        this.messages = messages;
        this.server = plugin.getServer();
        this.placeholders = placeholders;
        this.store = new PlayerStore<>(
                "profile",
                plugin,
                database,
                repository,
                new ProfileLifecycle(),
                () -> messages.render(Locale.ROOT, "profile.load-failed"),
                logger,
                clock);
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
        store.enable();
        placeholders.register("joins", player -> player == null
                ? null
                : find(player.getUniqueId()).map(profile -> String.valueOf(profile.joins())).orElse(null));
    }

    @Override
    public void disable() {
        placeholders.unregister("joins");
        store.disable();
    }

    /** The profile of an online player. Safe from any thread, never blocks. */
    public Optional<PlayerProfile> find(UUID id) {
        return store.find(id);
    }

    /**
     * Changes the profile of an online player, and saves it, without losing a concurrent change:
     * see {@link PlayerStore#update}. Safe from any thread, never blocks.
     *
     * @param change gives the new profile from the current one, as {@code PlayerProfile#joined} does
     * @return the new profile, or empty when the player is not online (nothing is changed or saved)
     */
    public Optional<PlayerProfile> update(UUID id, UnaryOperator<PlayerProfile> change) {
        return store.update(id, change);
    }

    /** Waits until everything queued so far has reached the database. For tests. */
    boolean awaitWrites(long timeoutSeconds) {
        return store.awaitWrites(timeoutSeconds);
    }

    /** The store behind the profiles, for the server test, which lives in this package. */
    PlayerStore<PlayerProfile> store() {
        return store;
    }

    /** {@code /foliate profile}, which shows the cached profile of an online player. */
    @Override
    public List<Subcommand> subcommands() {
        return List.of(new ProfileCommand(this::find, messages, server));
    }

    /** What happens to a profile when its player joins and leaves. */
    private static final class ProfileLifecycle implements PlayerLifecycle<PlayerProfile> {

        @Override
        public PlayerProfile create(UUID id, String name, long now) {
            return PlayerProfile.create(id, name, now);
        }

        @Override
        public PlayerProfile joined(PlayerProfile stored, String name, long now) {
            return stored.joined(name, now);
        }

        @Override
        public PlayerProfile left(PlayerProfile session, long now) {
            return session.seen(now);
        }
    }
}
