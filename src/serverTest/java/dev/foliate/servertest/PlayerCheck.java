package dev.foliate.servertest;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Tells whether a module did its part for a connected player. The server test asks every check once
 * a real client has joined, and again once it has left. {@link ServerTestPlugin} gives each enabled
 * module its check; a check lives in the module's package, in this source set, so that it can
 * read what the module keeps private to its package without the module publishing it.
 *
 * <p>Both methods are called off the tick threads, so they may block, and they must not touch the
 * world or anything of the player that is not safe to read from another thread (the name and the
 * unique id are).
 */
public interface PlayerCheck {

    /**
     * Called after the player joined, and again a little later when it returned a problem.
     *
     * @param visit 1 for the first time the server test sees the player, 2 when they come back
     * @return what is wrong, in a sentence that names the player, or null when all is well
     */
    @Nullable String problemWith(Player player, int visit);

    /**
     * Called after the player left, and again a little later when it returned a problem.
     *
     * @return what the module kept for a player who is gone, or null when it let everything go
     */
    default @Nullable String problemAfterQuit(UUID id, String name) {
        return null;
    }
}
