package dev.foliate.modules.metrics;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.FoliatePlugin;
import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.module.ModuleSkippedException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/** The two reasons not to report. Reporting itself needs bStats' servers and a real plugin id. */
class MetricsModuleTest {

    @TempDir
    Path directory;

    private FoliatePlugin plugin;
    private ConfigService<PluginConfig> config;

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        plugin = MockBukkit.load(FoliatePlugin.class);
        config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginWithoutABStatsIdDoesNotReport() {
        ModuleSkippedException skipped =
                assertThrows(ModuleSkippedException.class, new MetricsModule(plugin, () -> config.get().metrics(), 0)::enable);

        assertTrue(skipped.getMessage().contains("no bStats id"));
    }

    @Test
    void metricsTurnedOffInTheConfigurationDoNotReport() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "metrics: false\n");
        config.reload();

        ModuleSkippedException skipped =
                assertThrows(ModuleSkippedException.class, new MetricsModule(plugin, () -> config.get().metrics(), 12345)::enable);

        assertTrue(skipped.getMessage().contains("turned off"));
    }
}
