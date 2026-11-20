package dev.foliate.core.module;

import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Implemented by a module that can tell whether it did its part for a connected player. The
 * self-test asks every enabled module that implements this once a real client has joined, and
 * again once it has left, which is how a server with a player is checked without the self-test
 * knowing what any module does.
 *
 * <p>Both methods are called off the tick threads, so they may block, and they must not touch the
 * world or anything of the player that is not safe to read from another thread (the name and the
 * unique id are).
 */
public interface PlayerCheck {

    /**
     * Called after the player joined, and again a little later when it returned a problem.
     *
     * @return what is wrong, in a sentence that names the player, or null when all is well
     */
    @Nullable String problemWith(Player player);

    /**
     * Called after the player left, and again a little later when it returned a problem.
     *
     * @return what the module kept for a player who is gone, or null when it let everything go
     */
    default @Nullable String problemAfterQuit(UUID id, String name) {
        return null;
    }
}
