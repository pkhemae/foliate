package dev.foliate.core.placeholder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.foliate.testing.RecordingLogger;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class PlaceholderRegistryTest {

    private final RecordingLogger logger = new RecordingLogger();
    private final PlaceholderRegistry registry = new PlaceholderRegistry(logger);

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
        PlaceholderRegistry.Registration version = registry.register("version", player -> "1.0");

        version.unregister();

        assertNull(registry.resolve("version", null));
    }

    /** Two modules offering the same name is a mistake to see at once, not a value that changes. */
    @Test
    void aNameAlreadyTakenIsRefused() {
        registry.register("version", player -> "1.0");

        assertThrows(IllegalArgumentException.class, () -> registry.register("VERSION", player -> "2.0"));
        assertEquals("1.0", registry.resolve("version", null));
    }

    /** A module that unregisters late, or twice, cannot remove the placeholder another one took since. */
    @Test
    void unregisteringRemovesOnlyItsOwnPlaceholder() {
        PlaceholderRegistry.Registration first = registry.register("joins", player -> "1");
        first.unregister();
        registry.register("joins", player -> "2");

        first.unregister();

        assertEquals("2", registry.resolve("joins", null));
    }

    /** PlaceholderAPI would print the stack trace at every request, which can be every tick. */
    @Test
    void aFailingValueHasNoValueAndIsReportedOnce() {
        registry.register("broken", player -> {
            throw new IllegalStateException("boom");
        });

        assertNull(registry.resolve("broken", null));
        assertNull(registry.resolve("broken", null));

        assertEquals(List.of("ERROR Placeholder 'broken' failed and has no value until it is fixed"), logger.lines());
    }

    /** A placeholder that recovers and fails again later, say at a second outage, is reported again. */
    @Test
    void aFailureAfterARecoveryIsReportedAgain() {
        AtomicBoolean failing = new AtomicBoolean(true);
        registry.register("flaky", player -> {
            if (failing.get()) {
                throw new IllegalStateException("down");
            }
            return "up";
        });

        registry.resolve("flaky", null);
        failing.set(false);
        assertEquals("up", registry.resolve("flaky", null));
        failing.set(true);
        registry.resolve("flaky", null);

        assertEquals(2, logger.lines().size(), logger.lines().toString());
    }
}
