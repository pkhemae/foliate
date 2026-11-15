package dev.foliate.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * The service is tested with settings classes of its own, not the plugin's: it knows nothing about
 * what the settings are.
 */
class ConfigServiceTest {

    /** A settings class at version 1, with no migration. */
    @Configuration
    static final class Settings implements ConfigRoot {

        private int configVersion = 1;

        @Comment("Logs extra details that help when reporting a problem.")
        private boolean debug = false;

        private Settings() {}

        boolean debug() {
            return debug;
        }

        @Override
        public int configVersion() {
            return configVersion;
        }

        @Override
        public void validate() {}
    }

    /** The same settings after a format change: version 2, whose one step renames a key. */
    @Configuration
    static final class SettingsV2 implements ConfigRoot {

        private int configVersion = 2;
        private boolean debug = false;

        private SettingsV2() {}

        boolean debug() {
            return debug;
        }

        @Override
        public int configVersion() {
            return configVersion;
        }

        @Override
        public void validate() {}
    }

    private static final String HEADER = "Test configuration.";
    private static final List<Consumer<Map<String, Object>>> NO_MIGRATIONS = List.of();

    @TempDir
    Path directory;

    private Path file;
    private ConfigService<Settings> service;

    @BeforeEach
    void setUp() {
        file = directory.resolve("config.yml");
        service = serviceOf(Settings.class, Settings::new, NO_MIGRATIONS);
    }

    @Test
    void firstStartWritesACommentedFileWithDefaults() throws Exception {
        service.enable();

        assertFalse(service.get().debug());
        String written = Files.readString(file);
        assertTrue(written.contains("# Test configuration."), written);
        assertTrue(written.contains("debug: false"), written);
        assertTrue(written.contains("# Logs extra details"), written);
        assertTrue(written.contains("config-version: 1"), written);
    }

    @Test
    void customisedValuesAreReadAndKeptInTheFile() throws Exception {
        Files.writeString(file, "debug: true\n");

        service.enable();

        assertTrue(service.get().debug());
        assertTrue(Files.readString(file).contains("debug: true"));
    }

    @Test
    void keysMissingFromAnOlderFileAreAddedWithTheirDefault() throws Exception {
        Files.writeString(file, "config-version: 1\n");

        service.enable();

        assertTrue(Files.readString(file).contains("debug: false"));
    }

    @Test
    void emptyFileIsTreatedLikeAMissingOne() throws Exception {
        Files.writeString(file, "# only a comment\n");

        service.enable();

        assertTrue(Files.readString(file).contains("debug: false"));
    }

    @Test
    void unknownSettingsAreRemovedFromTheFile() throws Exception {
        Files.writeString(file, "debugg: true\n");

        service.enable();

        assertFalse(service.get().debug());
        assertFalse(Files.readString(file).contains("debugg"));
    }

    @Test
    void upToDateFileIsNotRewritten() throws Exception {
        service.enable();
        FileTime longAgo = FileTime.fromMillis(1_000_000);
        Files.setLastModifiedTime(file, longAgo);

        service.reload();

        assertEquals(longAgo, Files.getLastModifiedTime(file));
    }

    @Test
    void reloadPublishesANewConfiguration() throws Exception {
        service.enable();
        Settings before = service.get();

        Files.writeString(file, "debug: true\n");
        service.reload();

        assertNotSame(before, service.get());
        assertTrue(service.get().debug());
    }

    @Test
    void invalidValueOnReloadKeepsThePreviousConfigurationAndTheFile() throws Exception {
        Files.writeString(file, "debug: true\n");
        service.enable();
        Settings before = service.get();

        String invalid = "# mine\ndebug: [not, a, boolean]\n";
        Files.writeString(file, invalid);
        assertThrows(ConfigException.class, service::reload);

        assertSame(before, service.get());
        assertEquals(invalid, Files.readString(file));
    }

    @Test
    void brokenYamlIsRejectedWithAOneLineMessageAndLeftUntouched() throws IOException {
        String broken = "debug: [unclosed\n";
        Files.writeString(file, broken);

        ConfigException failure = assertThrows(ConfigException.class, service::enable);

        assertTrue(failure.getMessage().startsWith("config.yml is not valid YAML"), failure.getMessage());
        assertEquals(1, failure.getMessage().lines().count(), failure.getMessage());
        assertEquals(broken, Files.readString(file));
    }

    @Test
    void fileFromANewerPluginVersionIsRejected() throws IOException {
        Files.writeString(file, "config-version: 2\n");

        assertThrows(ConfigException.class, service::enable);
    }

    @Test
    void nonsenseVersionIsRejected() throws IOException {
        Files.writeString(file, "config-version: 0\n");

        assertThrows(ConfigException.class, service::enable);
    }

    @Test
    void readingBeforeLoadingFails() {
        assertThrows(IllegalStateException.class, service::get);
    }

    @Test
    void olderFileIsMigratedBackedUpAndLoaded() throws Exception {
        ConfigService<SettingsV2> migrating = serviceOf(
                SettingsV2.class,
                SettingsV2::new,
                List.of(yaml -> ConfigMigrator.rename(yaml, "verbose", "debug")));
        String old = "config-version: 1\nverbose: true\n";
        Files.writeString(file, old);

        migrating.enable();

        assertTrue(migrating.get().debug());
        String migrated = Files.readString(file);
        assertTrue(migrated.contains("config-version: 2"), migrated);
        assertFalse(migrated.contains("verbose"), migrated);
        assertEquals(old, Files.readString(directory.resolve("config.yml.v1.bak")));
    }

    /** Adding a migration step without bumping the settings class would run it again at every load. */
    @Test
    void settingsClassThatDoesNotMatchTheMigrationsIsAProgrammingError() {
        ConfigService<Settings> forgotten =
                serviceOf(Settings.class, Settings::new, List.of(yaml -> ConfigMigrator.rename(yaml, "a", "b")));

        assertThrows(IllegalStateException.class, forgotten::enable);
    }

    private <T extends ConfigRoot> ConfigService<T> serviceOf(
            Class<T> type, Supplier<T> defaults, List<Consumer<Map<String, Object>>> migrations) {
        return new ConfigService<>(directory, NOPLogger.NOP_LOGGER, type, defaults, migrations, HEADER);
    }
}
