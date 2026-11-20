package dev.foliate.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.foliate.core.yaml.Yaml;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/** The built-in language files say the same things: a key missing from one is English in the other. */
class BundledLanguagesTest {

    @Test
    void frenchDefinesEveryMessageEnglishDoes() throws Exception {
        assertEquals(keysOf("en"), keysOf("fr"));
    }

    private Set<String> keysOf(String language) throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/lang/" + language + ".yml")) {
            return new TreeSet<>(Yaml.flatten(Yaml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8))).keySet());
        }
    }
}
