package dev.foliate.core.player;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * What every {@link PlayerStore} must have done for a connected player, for the check of the
 * module that owns the store: a store is a part of a module, not a module, so it has no check of
 * its own.
 */
public final class PlayerStoreChecks {

    /** As long as the store gives its last saves when it shuts down. */
    private static final long FLUSH_TIMEOUT_SECONDS = 15;

    private PlayerStoreChecks() {}

    /** Is the value loaded, and has it reached the database? */
    public static @Nullable String problemWith(PlayerStore<?> store, Player player) {
        UUID id = player.getUniqueId();
        String what = "the " + store.subject() + " of " + player.getName();
        if (store.find(id).isEmpty()) {
            return what + " is not loaded";
        }
        if (!store.awaitWrites(FLUSH_TIMEOUT_SECONDS)) {
            return what + " was still being saved after " + FLUSH_TIMEOUT_SECONDS + " seconds";
        }
        try {
            if (store.stored(id).isEmpty()) {
                return what + " was not saved";
            }
        } catch (SQLException e) {
            return what + " could not be read back: " + e.getMessage();
        }
        return null;
    }

    /** Nothing of the player stays in memory once they left. */
    public static @Nullable String problemAfterQuit(PlayerStore<?> store, UUID id, String name) {
        return store.find(id).isPresent()
                ? "the " + store.subject() + " of " + name + " stayed in memory after the player left"
                : null;
    }

    /** What the database holds for the player. Blocks: call it after {@link #problemWith}. */
    public static <T extends PlayerData> Optional<T> stored(PlayerStore<T> store, UUID id) throws SQLException {
        return store.stored(id);
    }
}
