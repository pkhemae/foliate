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

        assertTrue(failure.getMessage().contains("'port'"), failure.getMessage());
        assertFalse(describe(failure).contains("s3cret-pw"), describe(failure));
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
