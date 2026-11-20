package dev.foliate.core.player;

import java.util.UUID;

/**
 * What is specific to one kind of player data in the three moments of a player's presence. All of
 * them run quickly, on any thread, and must not block.
 */
public interface PlayerLifecycle<T extends PlayerData> {

    /** The value of a player the store has never seen. */
    T create(UUID id, String name, long now);

    /**
     * A player joins: the value for their session, made from the stored one (or from
     * {@link #create}). It is cached and saved at once.
     */
    T joined(T stored, String name, long now);

    /** A player quits: the value to save, made from the one they had. */
    T left(T session, long now);
}
