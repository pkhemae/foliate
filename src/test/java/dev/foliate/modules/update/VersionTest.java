package dev.foliate.modules.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class VersionTest {

    @Test
    void numbersAreComparedAsNumbersNotAsText() {
        assertTrue(newer("1.10.0", "1.9.0"));
        assertTrue(newer("2.0.0", "1.99.99"));
    }

    @Test
    void leadingVAndMissingPartsDoNotMatter() {
        assertEquals(0, Version.parse("v1.4").compareTo(Version.parse("1.4.0")));
    }

    @Test
    void snapshotComesBeforeItsRelease() {
        assertTrue(newer("0.1.0", "0.1.0-SNAPSHOT"));
        assertTrue(newer("0.2.0-SNAPSHOT", "0.1.0"));
    }

    @Test
    void buildMetadataAndLongNumbersAreAccepted() {
        assertEquals(0, Version.parse("1.0.0+build.5").compareTo(Version.parse("1.0.0")));
        assertTrue(newer("20261115123456", "20251115123456"));
    }

    @Test
    void textThatIsNotAVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> Version.parse("latest"));
        assertThrows(IllegalArgumentException.class, () -> Version.parse("1.0.0.Final"));
    }

    private static boolean newer(String candidate, String current) {
        return Version.parse(candidate).compareTo(Version.parse(current)) > 0;
    }
}
