package dev.foliate.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/** MockBukkit is a Paper-like server: not Folia. The real answer on Folia is the server test's. */
class PlatformTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void aServerThatIsNotFoliaIsSaidSo() {
        assertFalse(Platform.isFolia());
    }

    @Test
    void theDescriptionNamesTheBrandAndTheVersion() {
        String description = Platform.describe();

        assertTrue(description.matches("\\S.* \\d+(\\.\\d+)*.*"), description);
    }
}
