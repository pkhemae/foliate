package dev.foliate.core.yaml;

import java.util.LinkedHashMap;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.exceptions.ComposerException;
import org.snakeyaml.engine.v2.exceptions.ConstructorException;
import org.snakeyaml.engine.v2.exceptions.DuplicateKeyException;
import org.snakeyaml.engine.v2.exceptions.MarkedYamlEngineException;
import org.snakeyaml.engine.v2.exceptions.ParserException;
import org.snakeyaml.engine.v2.exceptions.ReaderException;
import org.snakeyaml.engine.v2.exceptions.ScannerException;
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
            // Not chained: the library's message quotes the line, which may hold a password.
            throw new YamlException("not valid YAML (" + explain(e) + ")");
        }
        if (root == null) {
            return new LinkedHashMap<>();
        }
        if (!(root instanceof Map<?, ?> map)) {
            throw new YamlException("expected a list of settings, found a single value");
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

    /**
     * What is wrong, in fixed words, and where, such as {@code a key appears twice, line 6,
     * column 3}. Nothing is taken from the library's text: it repeats tokens of the file (an
     * alias, a tag, a key) and its marks quote the line, any of which may be a password.
     */
    private static String explain(YamlEngineException failure) {
        String problem = switch (failure) {
            case DuplicateKeyException duplicate -> "a key appears twice";
            case ComposerException alias -> "a value starts with * or &: put it in quotes";
            case ConstructorException tag -> "a value starts with !: put it in quotes";
            case ScannerException scanner -> "a quote, a bracket or the indentation is wrong";
            case ParserException parser -> "the indentation or a list is wrong";
            case ReaderException reader -> "it contains a character that is not allowed";
            default -> "a value cannot be read: put it in quotes";
        };
        if (failure instanceof MarkedYamlEngineException marked) {
            problem += marked.getProblemMark()
                    .map(mark -> ", line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1))
                    .orElse("");
        }
        return problem;
    }
}
