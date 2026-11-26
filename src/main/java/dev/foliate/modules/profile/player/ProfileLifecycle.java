package dev.foliate.modules.profile.player;

import dev.foliate.core.player.PlayerLifecycle;
import java.util.UUID;

/** What happens to a profile when its player joins and leaves. */
public final class ProfileLifecycle implements PlayerLifecycle<PlayerProfile> {

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
