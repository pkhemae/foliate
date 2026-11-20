package dev.foliate.core.module;

import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * Implemented by a module that can tell whether it did its part for a connected player. The
 * self-test asks every enabled module that implements this once a real client has joined, which
 * is how a server with a player is checked without the self-test knowing any module.
 */
public interface PlayerCheck {

    /**
     * Called off the tick threads, a few seconds after the player joined, so it may block.
     *
     * @return what is wrong, in a sentence that names the player, or null when all is well
     */
    @Nullable String problemWith(Player player);
}
