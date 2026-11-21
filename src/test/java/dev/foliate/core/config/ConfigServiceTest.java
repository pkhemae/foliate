package dev.foliate.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import de.exlll.configlib.SerializeWith;
import de.exlll.configlib.Serializer;
import dev.foliate.testing.RecordingLogger;
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

    /** Version 2 again, but one that refuses a setting the file can express: validation fails. */
    @Configuration
    static final class StrictSettings implements ConfigRoot {

        private int configVersion = 2;
        private boolean debug = false;

        private StrictSettings() {}

        @Override
        public int configVersion() {
            return configVersion;
        }

        @Override
        public void validate() throws ConfigException {
            if (debug) {
                throw new ConfigException("debug is not allowed here");
            }
        }
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

    /** Only the first line of the library's message would say where it is, not what is wrong. */
    @Test
    void brokenYamlSaysWhatIsWrongAndWhere() throws IOException {
        Files.writeString(file, "debug: false\nother: 1\ndebug: true\n");

        ConfigException failure = assertThrows(ConfigException.class, service::enable);

        assertEquals("config.yml is not valid YAML (a key appears twice, line 3, column 1)", failure.getMessage());
    }

    /** The position is given, never the line itself: it may hold a password. */
    @Test
    void brokenYamlDoesNotQuoteTheLine() throws IOException {
        Files.writeString(file, "debug: false\npassword: \"s3cret-pw\n");

        ConfigException failure = assertThrows(ConfigException.class, service::enable);

        assertFalse(failure.getMessage().contains("s3cret-pw"), failure.getMessage());
        assertTrue(failure.getMessage().contains("line"), failure.getMessage());
    }

    /** Nested known sections left empty are filled with their defaults, not reported. */
    @Test
    void anEmptyKnownSectionIsNotReported() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        ConfigService<PluginConfigShape> watched = new ConfigService<>(
                directory, logger, PluginConfigShape.class, PluginConfigShape::new, NO_MIGRATIONS, HEADER);
        Files.writeString(file, "section: {}\n");

        watched.enable();

        assertEquals(List.of(), logger.lines());
    }

    /** A settings class with a nested section. Its fields are read and written by ConfigLib only. */
    @SuppressWarnings("UnusedVariable")
    @Configuration
    static final class PluginConfigShape implements ConfigRoot {

        private int configVersion = 1;
        private Section section = new Section();

        private PluginConfigShape() {}

        @Override
        public int configVersion() {
            return configVersion;
        }

        @Override
        public void validate() {}

        @Configuration
        static final class Section {
            private boolean flag = true;
        }
    }

    @Test
    void anUnknownSectionIsReportedEvenWhenEmpty() throws Exception {
        Files.writeString(file, "debug: false\nleftover: {}\n");
        RecordingLogger logger = new RecordingLogger();
        ConfigService<Settings> watched =
                new ConfigService<>(directory, logger, Settings.class, Settings::new, NO_MIGRATIONS, HEADER);

        watched.enable();

        assertEquals(
                List.of("WARN config.yml: 'leftover' is not a setting of this plugin and was removed from the file"),
                logger.lines());
    }

    /** Saved by an editor in Windows-1252, an accent is not UTF-8: say so, not "Input length = 1". */
    @Test
    void aFileThatIsNotUtf8IsReportedAsSuch() throws IOException {
        Files.write(file, "debug: false\n# Bienvenue à tous\n".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1));

        ConfigException failure = assertThrows(ConfigException.class, service::enable);

        assertEquals("config.yml is not saved as UTF-8: save it again as UTF-8 in a text editor", failure.getMessage());
    }

    /** The message is read after the file's name. */
    @Test
    void aDocumentThatIsNotSettingsSaysWhatItIs() throws IOException {
        Files.writeString(file, "- debug\n- other\n");
        assertEquals("config.yml is not made of settings such as 'name: value' (it is a list)",
                assertThrows(ConfigException.class, service::enable).getMessage());

        Files.writeString(file, "just text\n");
        assertEquals("config.yml is not made of settings such as 'name: value' (it is a single value)",
                assertThrows(ConfigException.class, service::enable).getMessage());
    }

    /** A setting whose serializer reads a decimal into a whole number decides for itself. */
    @Test
    void aWholeNumberReadByASerializerOfItsOwnMayBeWrittenAsADecimal() throws Exception {
        ConfigService<TimedSettings> timed =
                new ConfigService<>(directory, NOPLogger.NOP_LOGGER, TimedSettings.class, TimedSettings::new, NO_MIGRATIONS, HEADER);
        timed.enable(); // writes cooldown: 1.5

        timed.reload();

        assertEquals(1_500L, timed.get().cooldownMillis);
        assertTrue(Files.readString(file).contains("cooldown-millis: 1.5"), Files.readString(file));
    }

    /** Milliseconds written as seconds, which a decimal says best. */
    public static final class Seconds implements Serializer<Long, Double> {

        public Seconds() {}

        @Override
        public Double serialize(Long millis) {
            return millis / 1000.0;
        }

        @Override
        public Long deserialize(Double seconds) {
            return Math.round(seconds * 1000);
        }
    }

    @Configuration
    static final class TimedSettings implements ConfigRoot {

        private int configVersion = 1;

        @SerializeWith(serializer = Seconds.class)
        private Long cooldownMillis = 1_500L;

        private TimedSettings() {}

        @Override
        public int configVersion() {
            return configVersion;
        }

        @Override
        public void validate() {}
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

    @Test
    void migrationThatLeadsToAnInvalidFileLeavesNoBackupAndTheFileUntouched() throws Exception {
        ConfigService<SettingsV2> migrating = serviceOf(
                SettingsV2.class,
                SettingsV2::new,
                List.of(yaml -> ConfigMigrator.rename(yaml, "verbose", "debug")));
        String old = "config-version: 1\nverbose: [not, a, boolean]\n";
        Files.writeString(file, old);

        assertThrows(ConfigException.class, migrating::enable);

        assertEquals(old, Files.readString(file));
        assertFalse(Files.exists(directory.resolve("config.yml.v1.bak")));
    }

    @Test
    void migrationThatFailsValidationLeavesNoBackupAndTheFileUntouched() throws Exception {
        ConfigService<StrictSettings> migrating = serviceOf(
                StrictSettings.class,
                StrictSettings::new,
                List.of(yaml -> ConfigMigrator.rename(yaml, "verbose", "debug")));
        String old = "config-version: 1\nverbose: true\n";
        Files.writeString(file, old);

        assertThrows(ConfigException.class, migrating::enable);

        assertEquals(old, Files.readString(file));
        assertFalse(Files.exists(directory.resolve("config.yml.v1.bak")));
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
