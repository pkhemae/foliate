package dev.foliate.modules.profile;

import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.foliate.FoliatePlugin;
import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.storage.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Not a test: writes the {@code data.db} that this version leaves, with a few rows of every table,
 * stored by the plugin's own code. Its dump becomes a snapshot under {@code upgrade/}, which
 * {@link DatabaseUpgradeTest} opens from then on; the README there says how. It runs only when
 * {@code FOLIATE_UPGRADE_SNAPSHOT} names the folder to write the database into.
 */
@EnabledIfEnvironmentVariable(named = "FOLIATE_UPGRADE_SNAPSHOT", matches = ".+")
class UpgradeSnapshotWriter {

    /**
     * Values that a column too narrow would not hold: the longest Minecraft name, and epochs in
     * milliseconds, which need a BIGINT. A module that brings a table stores rows of its own below.
     */
    private static final List<PlayerProfile> PROFILES = List.of(
            new PlayerProfile(UUID.fromString("3c0f1d4e-8a52-4b7e-9f10-2d6b5a7c9e01"),
                    "Alex", 1_731_000_000_000L, 1_763_700_000_000L, 214),
            new PlayerProfile(UUID.fromString("9b2e6a71-0c3d-4f58-a1e9-5f7d2c8b4a36"),
                    "Steve_The_Miner1", 1_763_000_000_000L, 1_763_000_000_000L, 1),
            new PlayerProfile(UUID.fromString("d41f8c09-6e2b-47a3-8c5d-0b9e3f1a7d24"),
                    "Notch", 1_700_000_000_000L, 1_763_650_000_000L, 3));

    @TempDir
    Path configDirectory;

    @Test
    void writeTheDatabaseOfThisVersion() throws Exception {
        Path folder = Path.of(System.getenv("FOLIATE_UPGRADE_SNAPSHOT")).toAbsolutePath();
        assertFalse(Files.exists(folder.resolve("data.db")), folder.resolve("data.db") + " exists: remove it first");
        ConfigService<PluginConfig> config = PluginConfig.service(configDirectory, NOPLogger.NOP_LOGGER);
        config.enable();
        Database database = new Database(folder, () -> config.get().storage(), FoliatePlugin.MIGRATIONS, NOPLogger.NOP_LOGGER);
        database.enable();
        try {
            ProfileRepository profiles = new ProfileRepository();
            database.call(connection -> {
                for (PlayerProfile profile : PROFILES) {
                    profiles.save(connection, profile);
                }
                return null;
            });
        } finally {
            database.disable();
        }
    }
}
