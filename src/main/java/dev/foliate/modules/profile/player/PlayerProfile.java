package dev.foliate.modules.profile.player;

import dev.foliate.core.player.PlayerData;
import java.util.UUID;

/**
 * What the plugin remembers about a player. Immutable: a change makes a new profile, which is what
 * lets any thread read the cached one without locking.
 *
 * @param firstSeen epoch milliseconds
 * @param lastSeen epoch milliseconds: the time of the current join while the player is online,
 *     the time they left after
 */
public record PlayerProfile(UUID id, String name, long firstSeen, long lastSeen, int joins) implements PlayerData {

    static PlayerProfile create(UUID id, String name, long now) {
        return new PlayerProfile(id, name, now, now, 0);
    }

    PlayerProfile joined(String currentName, long now) {
        return new PlayerProfile(id, currentName, firstSeen, now, joins + 1);
    }

    PlayerProfile seen(long now) {
        return new PlayerProfile(id, name, firstSeen, now, joins);
    }
}
