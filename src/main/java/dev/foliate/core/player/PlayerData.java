package dev.foliate.core.player;

import java.util.UUID;

/**
 * What a {@link PlayerStore} keeps for a player. Make it an immutable record: a change makes a new
 * value, which is what lets any thread read the cached one without locking.
 */
public interface PlayerData {

    /** The player this value belongs to; a change must keep it. */
    UUID id();
}
