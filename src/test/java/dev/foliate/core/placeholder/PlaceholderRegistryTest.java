package dev.foliate.core.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class PlaceholderRegistryTest {

    private final PlaceholderRegistry registry = new PlaceholderRegistry();

    @Test
    void resolvesARegisteredPlaceholderWhateverTheCase() {
        registry.register("Version", player -> "1.0");

        assertEquals("1.0", registry.resolve("version", null));
        assertEquals("1.0", registry.resolve("VERSION", null));
    }

    @Test
    void unknownPlaceholderHasNoValue() {
        assertNull(registry.resolve("missing", null));
    }

    @Test
    void unregisteredPlaceholderHasNoValue() {
        registry.register("version", player -> "1.0");

        registry.unregister("version");

        assertNull(registry.resolve("version", null));
    }
}
