package dev.foliate;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
}
