package dev.foliate.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.yaml.Yaml;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every message the code sends by a literal key is written in the English file. A key that is not
 * would show as the key itself, and only to whoever happens to trigger it.
 *
 * <p>Read from the sources: the calls of {@link MessageService} whose key is a string literal,
 * such as {@code messages.send(player, "menu.unknown", ...)}. A key held in a variable cannot be
 * checked here.
 */
class MessageKeysTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** A call that takes a key, and the positions of its arguments that are keys. */
    private static final Map<String, List<Integer>> KEY_ARGUMENTS = Map.of(
            "send", List.of(1),
            "sendActionBar", List.of(1),
            "render", List.of(1),
            "renderLines", List.of(1),
            "showTitle", List.of(1, 2));

    private static final Pattern CALL = Pattern.compile("\\bmessages\\s*\\.\\s*(\\w+)\\s*\\(");
    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyKeyUsedInTheCodeIsInTheEnglishFile() throws Exception {
        Set<String> defined = keysOf("en");
        Map<String, String> used = usedKeys();

        assertTrue(used.size() >= 10, "the scan found too few keys to be working: " + used);
        Map<String, String> missing = new TreeMap<>(used);
        missing.keySet().removeAll(defined);
        assertEquals(Map.of(), missing, "keys used in the code (key=file) but missing from lang/en.yml");
    }

    /** Each key used with a literal, and the first file that uses it. */
    private static Map<String, String> usedKeys() throws IOException {
        Map<String, String> used = new TreeMap<>();
        try (Stream<Path> files = Files.walk(SOURCES)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = read(file);
                Matcher call = CALL.matcher(source);
                while (call.find()) {
                    List<Integer> positions = KEY_ARGUMENTS.get(call.group(1));
                    if (positions == null) {
                        continue;
                    }
                    List<String> arguments = arguments(source, call.end());
                    for (int position : positions) {
                        if (position < arguments.size()) {
                            Matcher literal = LITERAL.matcher(arguments.get(position));
                            if (literal.matches()) {
                                used.putIfAbsent(literal.group(1), SOURCES.relativize(file).toString());
                            }
                        }
                    }
                }
            }
        }
        return used;
    }

    /** The arguments of a call, trimmed, from just after its opening parenthesis. */
    private static List<String> arguments(String source, int start) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int from = start;
        for (int i = start; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '"') {
                // Skip the literal, escaped quotes included.
                for (i++; i < source.length() && source.charAt(i) != '"'; i++) {
                    if (source.charAt(i) == '\\') {
                        i++;
                    }
                }
            } else if (c == '(' || c == '[' || c == '{') {
                depth++;
            } else if ((c == ')' || c == ']' || c == '}') && depth > 0) {
                depth--;
            } else if (c == ')' || (c == ',' && depth == 0)) {
                arguments.add(source.substring(from, i).strip());
                from = i + 1;
                if (c == ')') {
                    return arguments;
                }
            }
        }
        return arguments;
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Set<String> keysOf(String language) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + language + ".yml")) {
            return new TreeSet<>(Yaml.flatten(Yaml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).keySet());
        }
    }
}
