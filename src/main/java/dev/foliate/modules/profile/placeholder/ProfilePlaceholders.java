package dev.foliate.modules.profile.placeholder;

import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.modules.profile.player.PlayerProfile;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The profile's placeholders, {@code %foliate_<name>%} with PlaceholderAPI: {@code joins}, how many
 * times an online player joined. Safe from any thread: it reads the cache.
 */
public final class ProfilePlaceholders {

    private final Function<UUID, Optional<PlayerProfile>> profiles;

    /** @param profiles the cached profile of an online player */
    public ProfilePlaceholders(Function<UUID, Optional<PlayerProfile>> profiles) {
        this.profiles = profiles;
    }

    /** Registers the placeholders, and gives what unregisters them. */
    public PlaceholderRegistry.Registration register(PlaceholderRegistry registry) {
        return registry.register("joins", player -> player == null
                ? null
                : profiles.apply(player.getUniqueId()).map(profile -> String.valueOf(profile.joins())).orElse(null));
    }
}
