package dev.foliate.core.yaml;

import java.util.LinkedHashMap;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.YamlEngineException;

/**
 * Raw YAML as nested maps. Every file the plugin owns goes through this class or through ConfigLib,
 * which uses the same engine: YAML 1.2, where only {@code true} and {@code false} are booleans.
 *
 * <p>Bukkit's {@code YamlConfiguration} must not be used on these files. It speaks YAML 1.1, in
 * which {@code no}, {@code on} or {@code 12:30} are not strings, so a value written by an
 * administrator would silently change type when read by one and written back by the other.
 */
public final class Yaml {

    private Yaml() {}

    /**
     * Parses a document whose root is a mapping.
     *
     * @return the content, empty for an empty or comment-only document
     * @throws YamlException with a one-line message when the document cannot be used
     */
    public static Map<String, Object> parse(String document) throws YamlException {
        Object root;
        try {
            root = new Load(LoadSettings.builder().build()).loadFromString(document);
        } catch (YamlEngineException e) {
            throw new YamlException("not valid YAML (" + firstLine(e.getMessage()) + ")", e);
        }
        if (root == null) {
            return new LinkedHashMap<>();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new YamlException("expected a list of settings, found a single value", null);
        }
        return stringKeys(map);
    }

    public static String dump(Map<String, Object> content) {
        DumpSettings settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build();
        return new Dump(settings).dumpToString(content);
    }

    /**
     * Flattens nested mappings into dotted keys: {@code {a: {b: 1}}} becomes {@code {a.b: 1}}.
     * Lists and scalars are kept as values.
     */
    public static Map<String, Object> flatten(Map<String, Object> content) {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", content, flat);
        return flat;
    }

    private static void flatten(String prefix, Map<String, Object> content, Map<String, Object> flat) {
        content.forEach((key, value) -> {
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                flatten(path, stringKeys(nested), flat);
            } else {
                flat.put(path, value);
            }
        });
    }

    /** Copies a mapping, turning keys into strings and nested mappings into mutable maps. */
    private static Map<String, Object> stringKeys(Map<?, ?> map) {
        Map<String, Object> copy = new LinkedHashMap<>();
        map.forEach((key, value) ->
                copy.put(String.valueOf(key), value instanceof Map<?, ?> nested ? stringKeys(nested) : value));
        return copy;
    }

    private static String firstLine(String message) {
        return message == null ? "unknown error" : message.lines().findFirst().orElse("unknown error").strip();
    }
}
