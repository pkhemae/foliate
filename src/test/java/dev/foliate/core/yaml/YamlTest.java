package dev.foliate.core.yaml;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class YamlTest {

    /** Followed blindly, such a value is infinite: reading it used to overflow the stack. */
    @Test
    void valueThatContainsItselfIsRefused() {
        YamlException failure = assertThrows(YamlException.class, () -> Yaml.parse("a: &x {b: *x}\n"));

        assertEquals("not valid YAML (a value contains itself: an alias * inside the & it names,"
                + " line 1, column 11)", failure.getMessage());
    }

    @Test
    void anchorUsedOutsideItselfIsFine() throws Exception {
        Map<String, Object> content = Yaml.parse("a: &x {b: 1}\nc: *x\n");

        assertEquals(Map.of("a.b", 1, "c.b", 1), Yaml.flatten(content));
    }

    @Test
    void anchorRedefinedAsAScalarIsNotMistakenForItself() throws Exception {
        Map<String, Object> content = Yaml.parse("a: &x {b: &x 1, c: *x}\n");

        assertEquals(Map.of("a.b", 1, "a.c", 1), Yaml.flatten(content));
    }

    @Test
    void thousandsOfNestedSectionsAreRefused() {
        int levels = 10_000;
        String document = "a: " + "{b: ".repeat(levels) + "1" + "}".repeat(levels) + "\n";

        YamlException failure = assertThrows(YamlException.class, () -> Yaml.parse(document));

        assertTrue(failure.getMessage().startsWith("nested too deeply (more than 64 levels, line 1, column "),
                failure.getMessage());
    }

    @Test
    void thousandsOfNestedListsAreRefused() {
        int levels = 10_000;
        String document = "a: " + "[".repeat(levels) + "]".repeat(levels) + "\n";

        YamlException failure = assertThrows(YamlException.class, () -> Yaml.parse(document));

        assertTrue(failure.getMessage().startsWith("nested too deeply"), failure.getMessage());
    }

    @Test
    void sixtyFourLevelsAreFine() throws Exception {
        int levels = 63;
        String document = "a: " + "{b: ".repeat(levels) + "1" + "}".repeat(levels) + "\n";

        assertEquals(1, Yaml.flatten(Yaml.parse(document)).size());
    }

    /** Each alias stays within the limit, but what they build together does not. */
    @Test
    void aliasesThatBuildADeepStructureAreRefused() {
        String deep = "{b: ".repeat(40) + "1" + "}".repeat(40);
        String document = "a: &x " + deep + "\nc: " + "{d: ".repeat(40) + "*x" + "}".repeat(40) + "\n";

        YamlException failure = assertThrows(YamlException.class, () -> Yaml.parse(document));

        assertEquals("nested too deeply (more than 64 levels)", failure.getMessage());
    }

    /** The engine stops at 3 MB, with a message that used to come out as "a value cannot be read". */
    @Test
    void fileOverTheSizeLimitSaysItIsTooLarge() {
        String document = "a: '" + "x".repeat(3 * 1024 * 1024) + "'\n";

        YamlException failure = assertThrows(YamlException.class, () -> Yaml.parse(document));

        assertEquals("too large (more than 3 MB of text)", failure.getMessage());
    }

    /** It used to say that a value starts with * or &. */
    @Test
    void secondDocumentIsReportedAsSuch() {
        YamlException failure = assertThrows(YamlException.class, () -> Yaml.parse("a: 1\n---\nb: 2\n"));

        assertEquals("made of more than one document (a line --- starts a second one, line 2, column 1)", failure.getMessage());
    }

    @Test
    void documentThatStartsWithTheMarkerIsASingleOne() throws Exception {
        assertEquals(Map.of("a", 1), Yaml.parse("---\na: 1\n"));
    }

    @Test
    void flatteningAMapThatContainsItselfIsRefused() {
        Map<String, Object> content = new LinkedHashMap<>();
        content.put("self", content);

        YamlException failure = assertThrows(YamlException.class, () -> Yaml.flatten(content));

        assertEquals("nested too deeply (more than 64 levels)", failure.getMessage());
    }
}
