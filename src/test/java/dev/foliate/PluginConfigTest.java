package dev.foliate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.config.ConfigException;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.storage.StorageSettings;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class PluginConfigTest {

    @TempDir
    Path directory;

    /**
     * The {@code config.yml} a fresh install gets is the contract with administrators. Where a
     * setting lives in the code does not matter to them, so moving a settings class must not change
     * the file. When the file is meant to change, update {@code config-reference.yml} with it.
     */
    @Test
    void freshInstallWritesTheReferenceFile() throws Exception {
        PluginConfig.service(directory, NOPLogger.NOP_LOGGER).enable();

        String expected;
        try (InputStream in = getClass().getResourceAsStream("/config-reference.yml")) {
            expected = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertEquals(expected, Files.readString(directory.resolve("config.yml")));
    }

    @Test
    void driverPropertiesAreReadAsTheyAreWritten() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                storage:
                  mysql:
                    properties:
                      sslMode: REQUIRED
                      useSSL: false
                      connectTimeout: 5000
                """);
        ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

        service.enable();

        assertEquals(
                Map.of("sslMode", "REQUIRED", "useSSL", "false", "connectTimeout", "5000"),
                service.get().storage().mysql().properties());
    }

    /** A wrong value names the setting, and never prints the section around it with its password. */
    @Test
    void aDriverPropertyThatIsNotAValueIsRefusedWithoutShowingThePassword() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                storage:
                  mysql:
                    password: s3cret-pw
                    properties:
                      sessionVariables:
                        - wait_timeout=10
                """);
        ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

        ConfigException failure = assertThrows(ConfigException.class, service::enable);

        String everything = describe(failure);
        assertTrue(everything.contains("storage.mysql.properties.sessionVariables"), everything);
        assertFalse(everything.contains("s3cret-pw"), everything);
    }

    @Test
    void aWrongValueElsewhereDoesNotShowThePasswordEither() throws Exception {
        Files.writeString(directory.resolve("config.yml"), """
                storage:
                  mysql:
                    password: s3cret-pw
                    port: abc
                """);
        ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

        ConfigException failure = assertThrows(ConfigException.class, service::enable);

        assertEquals("config.yml is invalid: storage.mysql.port must be a whole number", failure.getMessage());
        assertFalse(describe(failure).contains("s3cret-pw"), describe(failure));
    }

    /**
     * Unquoted, a generated password that starts with {@code [} or <code>{</code> is a list or a
     * section in YAML, and the library quotes such values differently from a single one.
     */
    @Test
    void aPasswordThatYamlReadsAsAListOrASectionIsNeverShown() throws Exception {
        for (String password : new String[] {"[s3cret-a, s3cret-b]", "{s3cret-m: s3cret-n}"}) {
            Files.writeString(directory.resolve("config.yml"), "storage:\n  mysql:\n    password: " + password + "\n");
            ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

            ConfigException failure = assertThrows(ConfigException.class, service::enable);

            assertEquals("config.yml is invalid: storage.mysql.password must be text: put it in quotes", failure.getMessage());
            assertFalse(describe(failure).contains("s3cret"), describe(failure));
        }
    }

    /**
     * SnakeYAML's own messages repeat tokens of the file: an alias, a tag, or a number it failed
     * to read. A generated password can start with any of these signs.
     */
    @Test
    void aPasswordThatIsNotValidYamlIsNeverShown() throws Exception {
        for (String password : new String[] {"*s3cret", "!s3cret", "!!s3cret abc", "!!int s3cret", "\"s3cret", "@s3cret", "s3cret: x"}) {
            Files.writeString(directory.resolve("config.yml"), "storage:\n  mysql:\n    password: " + password + "\n");
            ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

            ConfigException failure = assertThrows(ConfigException.class, service::enable, password);

            assertFalse(describe(failure).contains("s3cret"), describe(failure));
        }
    }

    /** Each kind of mistake names the setting and what it takes, in the administrator's words. */
    @Test
    void aWrongValueSaysWhatTheSettingTakes() throws Exception {
        Map<String, String> expected = Map.of(
                "storage:\n  type: FOO\n", "storage.type must be one of SQLITE, MYSQL",
                "storage:\n  enabled: maybe\n", "storage.enabled must be true or false",
                "storage:\n  mysql: hello\n", "storage.mysql must be a section of settings",
                "storage:\n  mysql:\n    pool-size: 99999999999\n", "storage.mysql.pool-size must be a whole number",
                "scoreboard:\n  lines: hello\n", "scoreboard.lines must be a list (each entry: text)",
                "scoreboard:\n  lines:\n    - {a: b}\n", "scoreboard.lines must be a list (each entry: text)",
                "storage:\n  mysql:\n    password: 123456\n", "storage.mysql.password must be text: put it in quotes");
        for (Map.Entry<String, String> mistake : expected.entrySet()) {
            Files.writeString(directory.resolve("config.yml"), mistake.getKey());
            ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

            ConfigException failure = assertThrows(ConfigException.class, service::enable);

            assertEquals("config.yml is invalid: " + mistake.getValue(), failure.getMessage(), mistake.getKey());
        }
    }

    /** ConfigLib would read 1.5 as 1 and write 1 back: a whole-number setting refuses it. */
    @Test
    void aDecimalForAWholeNumberIsRefused() throws Exception {
        for (Map.Entry<String, String> decimal : Map.of(
                "scoreboard:\n  refresh-ticks: 1.5\n", "scoreboard.refresh-ticks must be a whole number",
                "storage:\n  mysql:\n    pool-size: 2.0\n", "storage.mysql.pool-size must be a whole number").entrySet()) {
            Files.writeString(directory.resolve("config.yml"), decimal.getKey());
            ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

            ConfigException failure = assertThrows(ConfigException.class, service::enable);

            assertEquals("config.yml is invalid: " + decimal.getValue(), failure.getMessage());
            assertEquals(decimal.getKey(), Files.readString(directory.resolve("config.yml")), "the file was rewritten");
        }
    }

    /** The section is not used then, so what is left in it must not stop the server. */
    @Test
    void theMysqlSectionIsOnlyCheckedWhenItIsUsed() throws Exception {
        for (String unused : new String[] {
            "storage:\n  type: SQLITE\n  mysql:\n    port: 0\n",
            "storage:\n  enabled: false\n  type: MYSQL\n  mysql:\n    port: 0\n",
        }) {
            Files.writeString(directory.resolve("config.yml"), unused);

            PluginConfig.service(directory, NOPLogger.NOP_LOGGER).enable();
        }
    }

    /** A blank name would only fail later, when the server takes the migration lock. */
    @Test
    void aBlankMysqlNameIsRefusedWhenMysqlIsUsed() throws Exception {
        for (String setting : new String[] {"host", "database", "username"}) {
            Files.writeString(directory.resolve("config.yml"),
                    "storage:\n  type: MYSQL\n  mysql:\n    " + setting + ": ' '\n");
            ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

            ConfigException failure = assertThrows(ConfigException.class, service::enable);

            assertTrue(failure.getMessage().contains("storage.mysql." + setting), failure.getMessage());
        }
    }

    /** Every message and stack trace line the logs would show for this failure. */
    private static String describe(Throwable failure) {
        java.io.StringWriter out = new java.io.StringWriter();
        failure.printStackTrace(new java.io.PrintWriter(out));
        return out.toString();
    }

    /** A key left empty in YAML is a null: the library keeps the default instead of failing later. */
    @Test
    void settingsLeftEmptyKeepTheirDefault() throws Exception {
        for (String empty : new String[] {
            "default-locale:\n",
            "scoreboard:\n  title:\n",
            "scoreboard:\n  lines:\n",
            "storage:\n  type:\n",
            "storage:\n  mysql:\n    properties:\n",
            "scoreboard:\n",
        }) {
            Files.writeString(directory.resolve("config.yml"), empty);
            ConfigService<PluginConfig> service = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);

            service.enable();

            assertEquals("en", service.get().defaultLocale(), empty);
            assertEquals("<aqua><bold>Foliate", service.get().scoreboard().title(), empty);
            assertEquals(4, service.get().scoreboard().lines().size(), empty);
            assertEquals(StorageSettings.Type.SQLITE, service.get().storage().type(), empty);
        }
    }
}
