package dev.foliate.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class ConfigServiceTest {

    @TempDir
    Path directory;

    private Path file;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        file = directory.resolve("config.yml");
        service = new ConfigService(directory, NOPLogger.NOP_LOGGER);
    }

    @Test
    void firstStartWritesACommentedFileWithDefaults() throws Exception {
        service.enable();

        assertFalse(service.get().debug());
        String written = Files.readString(file);
        assertTrue(written.contains("debug: false"), written);
        assertTrue(written.contains("# Logs extra details"), written);
        assertTrue(written.contains("config-version: " + ConfigMigrator.CURRENT_VERSION), written);
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
        Files.writeString(file, "config-version: " + ConfigMigrator.CURRENT_VERSION + "\n");

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
        PluginConfig before = service.get();

        Files.writeString(file, "debug: true\n");
        service.reload();

        assertNotSame(before, service.get());
        assertTrue(service.get().debug());
    }

    @Test
    void invalidValueOnReloadKeepsThePreviousConfigurationAndTheFile() throws Exception {
        Files.writeString(file, "debug: true\n");
        service.enable();
        PluginConfig before = service.get();

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
        Files.writeString(file, "config-version: " + (ConfigMigrator.CURRENT_VERSION + 1) + "\n");

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
        ConfigService migrating = new ConfigService(
                directory,
                NOPLogger.NOP_LOGGER,
                new ConfigMigrator(List.of(yaml -> ConfigMigrator.rename(yaml, "verbose", "debug"))));
        String old = "config-version: 1\nverbose: true\n";
        Files.writeString(file, old);

        migrating.enable();

        assertTrue(migrating.get().debug());
        String migrated = Files.readString(file);
        assertTrue(migrated.contains("config-version: 2"), migrated);
        assertFalse(migrated.contains("verbose"), migrated);
        assertEquals(old, Files.readString(directory.resolve("config.yml.v1.bak")));
    }
}
