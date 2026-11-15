package dev.foliate.core.placeholder;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

/**
 * The values this plugin exposes to the outside, independently of any placeholder plugin. Features
 * register their values here; hooks such as the PlaceholderAPI one read from it.
 *
 * <p>A placeholder can be requested from any thread, including region threads that do not own the
 * player. Its function must therefore only read thread-safe state (a concurrent cache, an immutable
 * snapshot) and must never touch the world or do I/O.
 */
public final class PlaceholderRegistry {

    private final Map<String, Function<@Nullable OfflinePlayer, @Nullable String>> placeholders =
            new ConcurrentHashMap<>();

    /**
     * Registers a placeholder, replacing any previous one with the same name.
     *
     * @param name case-insensitive name, exposed as {@code %foliate_<name>%} by PlaceholderAPI
     * @param value computes the value; the player is null when the request has no player, and a
     *     null result means "no value"
     */
    public void register(String name, Function<@Nullable OfflinePlayer, @Nullable String> value) {
        placeholders.put(name.toLowerCase(Locale.ROOT), value);
    }

    public void unregister(String name) {
        placeholders.remove(name.toLowerCase(Locale.ROOT));
    }

    /** Resolves a placeholder, or returns null when it is unknown or has no value. */
    public @Nullable String resolve(String name, @Nullable OfflinePlayer player) {
        Function<@Nullable OfflinePlayer, @Nullable String> value = placeholders.get(name.toLowerCase(Locale.ROOT));
        return value == null ? null : value.apply(player);
    }
}
