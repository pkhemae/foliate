package dev.foliate.core.config;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;

/**
 * Brings a configuration written by an older version of the plugin up to date before it is mapped
 * to the settings class.
 *
 * <p>Only needed when a key is renamed, moved or changes meaning: a new key with a default value
 * needs no migration. The steps are given to {@link ConfigService}; the step at index {@code i}
 * upgrades a file from version {@code i + 1}. Steps work on the raw content, as nested maps, and
 * can use {@link #rename}.
 */
public final class ConfigMigrator {

    static final String VERSION_KEY = "config-version";

    private final List<Consumer<Map<String, Object>>> steps;

    ConfigMigrator(List<Consumer<Map<String, Object>>> steps) {
        this.steps = List.copyOf(steps);
    }

    /** The version of the current format: the number of steps plus one. */
    int targetVersion() {
        return steps.size() + 1;
    }

    /**
     * Upgrades the content of an older file.
     *
     * @return the migrated content, or empty when there is nothing to migrate. A file without a
     *     version was not written by the plugin (it writes one on every load): it is taken to be
     *     in the current format, so that steps never run twice on the same content.
     */
    Optional<Map<String, Object>> migrate(Map<String, Object> content) throws ConfigException {
        Object declared = content.get(VERSION_KEY);
        if (declared == null) {
            return Optional.empty();
        }
        int target = steps.size() + 1;
        if (!(declared instanceof Integer version) || version < 1) {
            throw new ConfigException(VERSION_KEY + " must be a whole number between 1 and " + target);
        }
        if (version > target) {
            throw new ConfigException("the file was written by a newer version of the plugin ("
                    + VERSION_KEY + " " + version + ", this version reads up to " + target + ")");
        }
        if (version == target) {
            return Optional.empty();
        }
        Map<String, Object> migrated = deepCopy(content);
        for (int from = version; from < target; from++) {
            steps.get(from - 1).accept(migrated);
        }
        migrated.put(VERSION_KEY, target);
        return Optional.of(migrated);
    }

    /**
     * Moves a value to a new dotted path, keeping what the administrator had set. Meant for a
     * migration step: {@code yaml -> rename(yaml, "old-name", "section.new-name")}.
     */
    public static void rename(Map<String, Object> yaml, String from, String to) {
        Object value = remove(yaml, from);
        if (value != null) {
            put(yaml, to, value);
        }
    }

    private static @Nullable Object remove(Map<String, Object> yaml, String path) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            return yaml.remove(path);
        }
        return yaml.get(path.substring(0, dot)) instanceof Map<?, ?> nested
                ? remove(mutable(nested), path.substring(dot + 1))
                : null;
    }

    private static void put(Map<String, Object> yaml, String path, Object value) {
        int dot = path.indexOf('.');
        if (dot < 0) {
            yaml.put(path, value);
            return;
        }
        String head = path.substring(0, dot);
        if (!(yaml.get(head) instanceof Map<?, ?>)) {
            yaml.put(head, new LinkedHashMap<String, Object>());
        }
        put(mutable((Map<?, ?>) yaml.get(head)), path.substring(dot + 1), value);
    }

    /** Safe after {@link #deepCopy}: every nested mapping is then a map we created ourselves. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> mutable(Map<?, ?> nested) {
        return (Map<String, Object>) nested;
    }

    private static Map<String, Object> deepCopy(Map<?, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(String.valueOf(key), deepCopy(value)));
        return copy;
    }

    private static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            return deepCopy(map);
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            list.forEach(element -> copy.add(deepCopy(element)));
            return copy;
        }
        return value;
    }
}
