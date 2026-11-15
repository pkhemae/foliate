package dev.foliate.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigServiceTest {

    @TempDir
    Path directory;

    private Path file;
    private ConfigService service;

    @BeforeEach
    void setUp() {
        file = directory.resolve("config.yml");
        service = new ConfigService(directory);
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
    void customisedValuesAreRead() throws Exception {
        Files.writeString(file, "debug: true\n");

        service.enable();

        assertTrue(service.get().debug());
    }

    @Test
    void keysMissingFromAnOlderFileAreAddedWithTheirDefault() throws Exception {
        Files.writeString(file, "config-version: " + ConfigMigrator.CURRENT_VERSION + "\n");

        service.enable();

        assertTrue(Files.readString(file).contains("debug: false"));
    }

    @Test
    void invalidFileOnReloadKeepsThePreviousConfiguration() throws Exception {
        Files.writeString(file, "debug: true\n");
        service.enable();
        PluginConfig before = service.get();

        Files.writeString(file, "debug: [not, a, boolean]\n");
        assertThrows(ConfigException.class, service::reload);

        assertSame(before, service.get());
    }

    @Test
    void brokenYamlIsRejectedWithoutTouchingTheFile() throws IOException {
        String broken = "debug: [unclosed\n";
        Files.writeString(file, broken);

        ConfigException failure = assertThrows(ConfigException.class, service::enable);

        assertTrue(failure.getMessage().startsWith("config.yml"), failure.getMessage());
        assertEquals(broken, Files.readString(file));
    }

    @Test
    void fileFromANewerPluginVersionIsRejected() throws IOException {
        Files.writeString(file, "config-version: " + (ConfigMigrator.CURRENT_VERSION + 1) + "\n");

        assertThrows(ConfigException.class, service::enable);
    }

    @Test
    void readingBeforeLoadingFails() {
        assertThrows(IllegalStateException.class, service::get);
    }

    @Test
    void migrationRenamesKeysAndBumpsTheVersion() throws Exception {
        ConfigMigrator migrator = new ConfigMigrator(List.of(yaml -> ConfigMigrator.rename(yaml, "verbose", "debug")));
        Files.writeString(file, "config-version: 1\nverbose: true\n");

        migrator.migrate(file);

        String migrated = Files.readString(file);
        assertTrue(migrated.contains("debug: true"), migrated);
        assertFalse(migrated.contains("verbose"), migrated);
        assertTrue(migrated.contains("config-version: 2"), migrated);
    }

    @Test
    void migrationLeavesAnUpToDateFileUntouched() throws Exception {
        ConfigMigrator migrator = new ConfigMigrator(List.of(yaml -> ConfigMigrator.rename(yaml, "verbose", "debug")));
        String upToDate = "# kept as is\nconfig-version: 2\ndebug: true\n";
        Files.writeString(file, upToDate);

        migrator.migrate(file);

        assertEquals(upToDate, Files.readString(file));
    }
}
