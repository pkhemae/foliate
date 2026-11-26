package dev.foliate.modules.profile.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import dev.foliate.FoliatePlugin;
import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.storage.Database;
import dev.foliate.modules.profile.player.PlayerProfile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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
     * Values that a column too narrow would not hold (the longest Minecraft name, and epochs in
     * milliseconds, which need a BIGINT), and what real tables hold that a unique index would
     * refuse: the same name on two players, and names that differ by case only. A module that
     * brings a table stores rows of its own below, as varied as its real data.
     */
    private static final List<PlayerProfile> PROFILES = List.of(
            new PlayerProfile(UUID.fromString("3c0f1d4e-8a52-4b7e-9f10-2d6b5a7c9e01"),
                    "Alex", 1_731_000_000_000L, 1_763_700_000_000L, 214),
            new PlayerProfile(UUID.fromString("9b2e6a71-0c3d-4f58-a1e9-5f7d2c8b4a36"),
                    "Steve_The_Miner1", 1_763_000_000_000L, 1_763_000_000_000L, 1),
            new PlayerProfile(UUID.fromString("d41f8c09-6e2b-47a3-8c5d-0b9e3f1a7d24"),
                    "Notch", 1_700_000_000_000L, 1_763_650_000_000L, 3),
            // Another player under the name Alex had before: a name is only updated at login, so
            // it stays on the player who left it.
            new PlayerProfile(UUID.fromString("5e7a2b90-1f4c-4d8e-b6a3-7c9d0e1f2a3b"),
                    "Alex", 1_650_000_000_000L, 1_690_000_000_000L, 57),
            // A name that differs from another one by its case only, which MySQL compares as equal.
            new PlayerProfile(UUID.fromString("8a6d3e15-2b7f-4c90-9e41-6f0a1b2c3d4e"),
                    "notch", 1_762_000_000_000L, 1_762_100_000_000L, 2));

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

            // A table left empty here would be empty in the snapshot, and its later migrations
            // never tried against rows: a module that brings a table stores rows of its own above.
            List<String> empty = database.call(connection -> {
                List<String> tables = new ArrayList<>();
                try (Statement statement = connection.createStatement();
                        ResultSet rows = statement.executeQuery("SELECT name FROM sqlite_master"
                                + " WHERE type = 'table' AND name NOT LIKE 'sqlite~_%' ESCAPE '~'")) {
                    while (rows.next()) {
                        tables.add(rows.getString(1));
                    }
                }
                List<String> withoutRows = new ArrayList<>();
                for (String table : tables) {
                    try (Statement statement = connection.createStatement();
                            ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM \"" + table + "\"")) {
                        if (rows.next() && rows.getInt(1) == 0) {
                            withoutRows.add(table);
                        }
                    }
                }
                return withoutRows;
            });
            assertEquals(List.of(), empty, "Tables to which UpgradeSnapshotWriter stores no rows");
        } finally {
            database.disable();
        }
    }
}
