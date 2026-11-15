package dev.foliate.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.yaml.Yaml;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ConfigMigratorTest {

    private final ConfigMigrator migrator = new ConfigMigrator(List.of(
            yaml -> ConfigMigrator.rename(yaml, "verbose", "debug"),
            yaml -> ConfigMigrator.rename(yaml, "debug", "logging.debug")));

    @Test
    void stepsRunInOrderFromTheFileVersion() throws Exception {
        Map<String, Object> migrated = migrate("config-version: 1\nverbose: true\n").orElseThrow();

        assertEquals(Map.of("config-version", 3, "logging.debug", true), Yaml.flatten(migrated));
    }

    @Test
    void stepsAlreadyAppliedAreSkipped() throws Exception {
        Map<String, Object> migrated = migrate("config-version: 2\nverbose: true\ndebug: false\n").orElseThrow();

        assertEquals(Map.of("config-version", 3, "verbose", true, "logging.debug", false), Yaml.flatten(migrated));
    }

    @Test
    void upToDateContentNeedsNoMigration() throws Exception {
        assertTrue(migrate("config-version: 3\ndebug: true\n").isEmpty());
    }

    @Test
    void contentWithoutAVersionIsTakenAsCurrentSoStepsNeverRunTwice() throws Exception {
        assertTrue(migrate("debug: true\n").isEmpty());
    }

    /**
     * Values an administrator wrote must come out of a migration exactly as they went in. With
     * YAML 1.1 these would turn into a boolean, a number, a number and a date.
     */
    @Test
    void migrationDoesNotChangeTheTypeOfValues() throws Exception {
        Map<String, Object> migrated =
                migrate("config-version: 1\nlocale: no\ntime: 12:30\ncode: 010\nday: 2026-11-15\n").orElseThrow();

        String written = Yaml.dump(migrated);
        Map<String, Object> reread = Yaml.flatten(Yaml.parse(written));
        assertEquals("no", reread.get("locale"));
        assertEquals("12:30", reread.get("time"));
        assertEquals("2026-11-15", reread.get("day"));
        assertEquals(Yaml.flatten(Yaml.parse("code: 010\n")).get("code"), reread.get("code"));
    }

    @Test
    void migrationDoesNotModifyItsInput() throws Exception {
        Map<String, Object> content = Yaml.parse("config-version: 1\nverbose: true\n");

        migrator.migrate(content);

        assertTrue(content.containsKey("verbose"));
        assertFalse(content.containsKey("logging"));
    }

    private Optional<Map<String, Object>> migrate(String document) throws Exception {
        return migrator.migrate(Yaml.parse(document));
    }
}
