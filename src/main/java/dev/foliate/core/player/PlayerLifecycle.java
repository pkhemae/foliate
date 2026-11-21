package dev.foliate.core.player;

import java.util.UUID;

/**
 * What is specific to one kind of player data in the three moments of a player's presence. All of
 * them run quickly, on any thread, under a lock the store uses for its ordering, so they must not
 * block, must not call back into the store, and must keep the player's id. A method that throws, or
 * returns another player's value, is logged and the value is kept as it was.
 */
public interface PlayerLifecycle<T extends PlayerData> {

    /** The value of a player the store has never seen. */
    T create(UUID id, String name, long now);

    /**
     * A player joins: the value for their session, made from the stored one (or from
     * {@link #create}), or from the one they already have when they log in a second time while
     * still online. It is cached and saved at once.
     *
     * <p>Players already online when the store starts get it too: their session counts from then.
     * Enabling the plugin while they play (Paper's {@code /reload}, a plugin manager) ends their
     * session with {@link #left} and starts a new one with this.
     */
    T joined(T stored, String name, long now);

    /** A player quits: the value to save, made from the one they had. */
    T left(T session, long now);
}
