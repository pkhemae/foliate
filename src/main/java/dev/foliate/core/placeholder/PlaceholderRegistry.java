package dev.foliate.core.placeholder;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The values this plugin exposes to the outside, independently of any placeholder plugin. Features
 * register their values here; hooks such as the PlaceholderAPI one read from it.
 *
 * <p>A placeholder can be requested from any thread, including region threads that do not own the
 * player. Its function must therefore only read thread-safe state (a concurrent cache, an immutable
 * snapshot) and must never touch the world or do I/O.
 */
public final class PlaceholderRegistry {

    /** Gives a placeholder back: what {@link #register} returns to whoever registered it. */
    @FunctionalInterface
    public interface Registration {

        /** Removes the placeholder, if it is still this one. Calling it again does nothing. */
        void unregister();
    }

    /**
     * A registered placeholder. Compared by identity, so that unregistering removes this
     * registration and no other, even one of the same function.
     */
    private static final class Entry {

        private final Function<@Nullable OfflinePlayer, @Nullable String> value;

        Entry(Function<@Nullable OfflinePlayer, @Nullable String> value) {
            this.value = value;
        }
    }

    private final Map<String, Entry> placeholders = new ConcurrentHashMap<>();
    private final Set<String> reportedFailures = ConcurrentHashMap.newKeySet();
    private final Logger logger;

    public PlaceholderRegistry(Logger logger) {
        this.logger = logger;
    }

    /**
     * Registers a placeholder under a name no other part of the plugin uses.
     *
     * @param name case-insensitive name, exposed as {@code %foliate_<name>%} by PlaceholderAPI
     * @param value computes the value; the player is null when the request has no player, and a
     *     null result means "no value"
     * @return what removes this placeholder, and only this one, when the part that registered it
     *     stops
     * @throws IllegalArgumentException when the name is already registered: two parts of the plugin
     *     would otherwise replace each other's value without a word
     */
    public Registration register(String name, Function<@Nullable OfflinePlayer, @Nullable String> value) {
        String key = name.toLowerCase(Locale.ROOT);
        Entry entry = new Entry(value);
        if (placeholders.putIfAbsent(key, entry) != null) {
            throw new IllegalArgumentException("The placeholder '" + key + "' is already registered");
        }
        reportedFailures.remove(key);
        return () -> placeholders.remove(key, entry);
    }

    /**
     * Resolves a placeholder, or returns null when it is unknown or has no value. A placeholder
     * that throws has no value: the failure is logged once per name, not at every request, and
     * never reaches the caller, such as PlaceholderAPI, which would print it each time.
     */
    public @Nullable String resolve(String name, @Nullable OfflinePlayer player) {
        String key = name.toLowerCase(Locale.ROOT);
        Entry entry = placeholders.get(key);
        if (entry == null) {
            return null;
        }
        try {
            return entry.value.apply(player);
        } catch (RuntimeException e) {
            if (reportedFailures.add(key)) {
                logger.error("Placeholder '{}' failed and has no value until it is fixed", key, e);
            }
            return null;
        }
    }
}
