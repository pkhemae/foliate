package dev.foliate.core.yaml;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.snakeyaml.engine.v2.api.Dump;
import org.snakeyaml.engine.v2.api.DumpSettings;
import org.snakeyaml.engine.v2.api.Load;
import org.snakeyaml.engine.v2.api.LoadSettings;
import org.snakeyaml.engine.v2.api.lowlevel.Parse;
import org.snakeyaml.engine.v2.common.FlowStyle;
import org.snakeyaml.engine.v2.events.AliasEvent;
import org.snakeyaml.engine.v2.events.CollectionEndEvent;
import org.snakeyaml.engine.v2.events.CollectionStartEvent;
import org.snakeyaml.engine.v2.events.DocumentStartEvent;
import org.snakeyaml.engine.v2.events.Event;
import org.snakeyaml.engine.v2.events.NodeEvent;
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

    /**
     * How deep sections and lists may be nested. Far more than any file of the plugin needs, and
     * little enough for the libraries, which read nested values by recursion, to stay well within
     * the stack.
     */
    static final int MAX_DEPTH = 64;

    /**
     * How many values a document may hold once its aliases are expanded: keys, values, sections
     * and lists all count. Hundreds of times what a file of the plugin holds, and few enough to
     * copy without a thought.
     */
    static final long MAX_VALUES = 100_000;

    private Yaml() {}

    /**
     * Parses a document whose root is a mapping.
     *
     * @return the content, empty for an empty or comment-only document
     * @throws YamlException with a one-line message when the document cannot be used
     */
    public static Map<String, Object> parse(String document) throws YamlException {
        LoadSettings settings = LoadSettings.builder().build();
        Object root;
        try {
            checkShape(settings, document);
            root = new Load(settings).loadFromString(document);
        } catch (YamlEngineException e) {
            // Not chained: the library's message quotes the line, which may hold a password.
            throw new YamlException(explain(e, document, settings));
        }
        if (root == null) {
            return new LinkedHashMap<>();
        }
        if (!(root instanceof Map<?, ?> map)) {
            // Read after the file's name: "config.yml is not made of settings such as 'name: value'".
            throw new YamlException("not made of settings such as 'name: value' (it is "
                    + (root instanceof List<?> ? "a list" : "a single value") + ")");
        }
        return stringKeys(map, 1);
    }

    /**
     * Refuses, before the document is turned into values, what would make that recursion endless
     * or too deep for the stack: a collection nested more than {@value #MAX_DEPTH} levels deep, or
     * one that contains itself through an alias ({@code a: &x {b: *x}}). Only the events of the
     * parser are read here, which it produces without recursion, whatever the depth.
     *
     * <p>Also refused here, where each can be told apart from other mistakes without reading the
     * library's message:
     *
     * <ul>
     *   <li>more values than {@value #MAX_VALUES} once every alias is replaced by what it names.
     *       Each level of {@code x1: &x1 {a: *x0, b: *x0}} doubles the document, and reading it
     *       copies every value: a few hundred bytes could run the server out of memory;
     *   <li>more aliases of collections than the engine accepts;
     *   <li>a second document in the same file.
     * </ul>
     */
    private static void checkShape(LoadSettings settings, String document) throws YamlException {
        // The latest node defined with each anchor name: what an alias to it stands for.
        Map<String, Node> anchors = new HashMap<>();
        Deque<Node> open = new ArrayDeque<>();
        long values = 0;
        int aliasesOfCollections = 0;
        int documents = 0;
        for (Event event : new Parse(settings).parseString(document)) {
            switch (event) {
                case DocumentStartEvent start -> {
                    documents++;
                    if (documents > 1) {
                        throw new YamlException(
                                "made of more than one document (a line --- starts a second one" + position(event) + ")");
                    }
                }
                case AliasEvent alias -> {
                    Node node = anchors.get(alias.getAlias().getValue());
                    if (node == null) {
                        // Unknown anchor: the engine refuses it, with its own position.
                        values++;
                    } else if (node.open) {
                        throw new YamlException("not valid YAML (a value contains itself: an alias * inside the & it names"
                                + position(event) + ")");
                    } else {
                        if (node.collection && ++aliasesOfCollections > settings.getMaxAliasesForCollections()) {
                            throw new YamlException("not valid YAML (more than " + settings.getMaxAliasesForCollections()
                                    + " aliases (*) of sections or lists" + position(event) + ")");
                        }
                        values += node.size;
                    }
                }
                case CollectionStartEvent start -> {
                    if (open.size() == MAX_DEPTH) {
                        throw new YamlException(
                                "nested too deeply (more than " + MAX_DEPTH + " levels" + position(event) + ")");
                    }
                    Node node = new Node(true, values);
                    values++;
                    start.getAnchor().ifPresent(anchor -> anchors.put(anchor.getValue(), node));
                    open.push(node);
                }
                case CollectionEndEvent end -> {
                    Node node = open.pop();
                    node.open = false;
                    node.size = values - node.start;
                }
                case NodeEvent scalar -> {
                    Node node = new Node(false, values);
                    values++;
                    scalar.getAnchor().ifPresent(anchor -> anchors.put(anchor.getValue(), node));
                }
                default -> {}
            }
            if (values > MAX_VALUES) {
                throw new YamlException("too large once its aliases (*) are expanded (more than " + MAX_VALUES
                        + " values" + position(event) + ")");
            }
        }
    }

    /**
     * A node of the document, as the shape check sees it: a value, or a collection, which is open
     * until its end and then knows how many values it holds, aliases expanded.
     */
    private static final class Node {

        final boolean collection;
        /** The number of values counted before this node. */
        final long start;
        boolean open;
        long size = 1;

        Node(boolean collection, long start) {
            this.collection = collection;
            this.start = start;
            this.open = collection;
        }
    }

    private static String position(Event event) {
        return event.getStartMark()
                .map(mark -> ", line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1))
                .orElse("");
    }

    public static String dump(Map<String, Object> content) {
        DumpSettings settings = DumpSettings.builder().setDefaultFlowStyle(FlowStyle.BLOCK).build();
        return new Dump(settings).dumpToString(content);
    }

    /**
     * Flattens nested mappings into dotted keys: {@code {a: {b: 1}}} becomes {@code {a.b: 1}}.
     * Lists and scalars are kept as values.
     *
     * @throws YamlException when the mappings are nested more than {@value #MAX_DEPTH} levels deep,
     *     which a mapping that contains itself always is
     */
    public static Map<String, Object> flatten(Map<String, Object> content) throws YamlException {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten("", content, flat, 1);
        return flat;
    }

    private static void flatten(String prefix, Map<?, ?> content, Map<String, Object> flat, int depth)
            throws YamlException {
        requireDepth(depth);
        for (Map.Entry<?, ?> entry : content.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (entry.getValue() instanceof Map<?, ?> nested) {
                flatten(path, nested, flat, depth + 1);
            } else {
                flat.put(path, entry.getValue());
            }
        }
    }

    /**
     * Copies a mapping, turning keys into strings and nested mappings into mutable maps. The
     * document's depth was checked, but an alias inserts a whole collection, nested as deep as it
     * is, wherever it is used: the copy is checked again.
     */
    private static Map<String, Object> stringKeys(Map<?, ?> map, int depth) throws YamlException {
        requireDepth(depth);
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()),
                    entry.getValue() instanceof Map<?, ?> nested ? stringKeys(nested, depth + 1) : entry.getValue());
        }
        return copy;
    }

    private static void requireDepth(int depth) throws YamlException {
        if (depth > MAX_DEPTH) {
            throw new YamlException("nested too deeply (more than " + MAX_DEPTH + " levels)");
        }
    }

    /**
     * What is wrong, in fixed words, and where, such as {@code not valid YAML (a key appears
     * twice, line 6, column 3)}. Nothing is taken from the library's text: it repeats tokens of the
     * file (an alias, a tag, a key) and its marks quote the line, any of which may be a password.
     * The kind of mistake is told by the type of the exception, and a document too long for the
     * engine by its length.
     */
    private static String explain(YamlEngineException failure, String document, LoadSettings settings) {
        int limit = settings.getCodePointLimit();
        if (failure.getClass() == YamlEngineException.class && document.codePointCount(0, document.length()) > limit) {
            return "too large (more than " + limit / (1024 * 1024) + " MB of text)";
        }
        String problem = switch (failure) {
            case DuplicateKeyException duplicate -> "a key appears twice";
            case ComposerException alias -> "a value starts with * or &: put it in quotes";
            case ConstructorException tag -> "a value starts with !: put it in quotes";
            case ScannerException scanner -> "a quote, a bracket or the indentation is wrong";
            case ParserException parser -> "the indentation or a list is wrong";
            case ReaderException reader -> "it contains a character that is not allowed";
            default -> "a value cannot be read; if it is text, put it in quotes";
        };
        if (failure instanceof MarkedYamlEngineException marked) {
            problem += marked.getProblemMark()
                    .map(mark -> ", line " + (mark.getLine() + 1) + ", column " + (mark.getColumn() + 1))
                    .orElse("");
        }
        return "not valid YAML (" + problem + ")";
    }
}
