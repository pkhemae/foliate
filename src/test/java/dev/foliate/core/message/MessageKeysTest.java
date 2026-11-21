package dev.foliate.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.command.PlayerTarget;
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
 * Every message the code sends by a literal key is written in messages.yml. A key that is not
 * would show as the key itself, and only to whoever happens to trigger it.
 *
 * <p>Read from the sources: the calls of {@link MessageService} whose key is a string literal,
 * such as {@code messages.send(player, "menu.unknown", ...)}, and the sections given to a
 * {@link PlayerTarget}, which reads its refusals under them. A key held in a variable cannot be
 * checked here.
 */
class MessageKeysTest {

    private static final Path SOURCES = Path.of("src/main/java");

    /** A call that takes a key, and the positions of its arguments that are keys. */
    private static final Map<String, List<Integer>> KEY_ARGUMENTS = Map.of(
            "send", List.of(1),
            "sendActionBar", List.of(1),
            // render(viewer, key) and, for text with no viewer, render(key): only a literal counts.
            "render", List.of(0, 1),
            "renderLines", List.of(1),
            "showTitle", List.of(1, 2));

    /**
     * A call of one of those methods on any receiver ({@code messages}, {@code this.messages},
     * {@code plugin.messages()}): the method's name and the position of its key tell it apart from
     * the audience methods of the same name, which take no text.
     */
    private static final Pattern CALL = Pattern.compile("\\.\\s*(\\w+)\\s*\\(");
    /** A {@link PlayerTarget}, whose fourth argument is the section of its messages. */
    private static final Pattern PLAYER_TARGET = Pattern.compile("new\\s+PlayerTarget\\s*\\(");

    private static final int PLAYER_TARGET_SECTION = 3;

    private static final Pattern LITERAL = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    @Test
    void everyKeyUsedInTheCodeIsInTheMessagesFile() throws Exception {
        Set<String> defined = definedKeys();
        Map<String, String> used = usedKeys();

        assertTrue(used.size() >= 10, "the scan found too few keys to be working: " + used);
        Map<String, String> missing = new TreeMap<>(used);
        missing.keySet().removeAll(defined);
        assertEquals(Map.of(), missing, "keys used in the code (key=file) but missing from messages.yml");
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
                Matcher target = PLAYER_TARGET.matcher(source);
                while (target.find()) {
                    List<String> arguments = arguments(source, target.end());
                    if (PLAYER_TARGET_SECTION < arguments.size()) {
                        Matcher literal = LITERAL.matcher(arguments.get(PLAYER_TARGET_SECTION));
                        if (literal.matches()) {
                            for (String key : PlayerTarget.messageKeys(literal.group(1))) {
                                used.putIfAbsent(key, SOURCES.relativize(file).toString());
                            }
                        }
                    }
                }
            }
        }
        return used;
    }

    /**
     * The arguments of a call, trimmed, from just after its opening parenthesis. Strings, text
     * blocks, character literals and comments are skipped, so a parenthesis or a comma in them does
     * not end an argument.
     */
    static List<String> arguments(String source, int start) {
        List<String> arguments = new ArrayList<>();
        int depth = 0;
        int from = start;
        int i = start;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (source.startsWith("\"\"\"", i)) {
                int end = source.indexOf("\"\"\"", i + 3);
                i = end < 0 ? source.length() : end + 3;
                continue;
            }
            if (c == '"' || c == '\'') {
                i = endOfLiteral(source, i, c);
                continue;
            }
            if (source.startsWith("//", i)) {
                int end = source.indexOf('\n', i);
                i = end < 0 ? source.length() : end;
                continue;
            }
            if (source.startsWith("/*", i)) {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
                continue;
            }
            if (c == '(' || c == '[' || c == '{') {
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
            i++;
        }
        return arguments;
    }

    /** Just after the string or character literal that starts at {@code start}, escapes included. */
    private static int endOfLiteral(String source, int start, char quote) {
        int i = start + 1;
        while (i < source.length() && source.charAt(i) != quote) {
            i += source.charAt(i) == '\\' ? 2 : 1;
        }
        return i + 1;
    }

    @Test
    void argumentsSkipWhatLooksLikeTheEndOfACall() {
        String source = "send(player, \"a.b\", ')', '\\'', \"x)\" /* ) */, f(1, 2), // )\n last) tail";

        List<String> arguments = arguments(source, "send(".length());

        assertEquals(List.of("player", "\"a.b\"", "')'", "'\\''", "\"x)\" /* ) */", "f(1, 2)", "// )\n last"),
                arguments);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Set<String> definedKeys() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/" + MessageService.FILE)) {
            return new TreeSet<>(Yaml.flatten(Yaml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).keySet());
        }
    }
}
