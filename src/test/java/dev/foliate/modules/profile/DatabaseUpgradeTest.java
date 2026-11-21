package dev.foliate.modules.profile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.foliate.FoliatePlugin;
import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.storage.Database;
import dev.foliate.core.storage.SqlDump;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.helpers.NOPLogger;

/**
 * This version must start on the database that each earlier one left, and read its data back:
 * every other test starts from an empty database. The snapshots are frozen under
 * {@code src/test/resources/upgrade/}, one folder each, and the README there says how to add one
 * at each release. Each is loaded into a fresh database, which then starts with the plugin's own
 * migrations, as a server that updates the plugin would.
 *
 * <p>On SQLite always; on MySQL and MariaDB too where the environment gives a server, as
 * {@link TestMysql} describes.
 *
 * <p>A failure here means that a release would break the servers that update to it. Do not change
 * a snapshot to make it pass: fix the plugin, or write the migration that brings the old database
 * to the new schema.
 */
class DatabaseUpgradeTest {

    /**
     * What each snapshot holds, as this version reads it back: the rows the version that wrote it
     * stored. When a version changes how the data is read (a new field with a default value), the
     * expected values change with it; the snapshots never do.
     */
    private static final Map<String, List<PlayerProfile>> SNAPSHOTS = Map.of(
            "unreleased-554be0d", List.of(
                    new PlayerProfile(UUID.fromString("3c0f1d4e-8a52-4b7e-9f10-2d6b5a7c9e01"),
                            "Alex", 1_731_000_000_000L, 1_763_700_000_000L, 214),
                    new PlayerProfile(UUID.fromString("9b2e6a71-0c3d-4f58-a1e9-5f7d2c8b4a36"),
                            "Steve_The_Miner1", 1_763_000_000_000L, 1_763_000_000_000L, 1),
                    new PlayerProfile(UUID.fromString("d41f8c09-6e2b-47a3-8c5d-0b9e3f1a7d24"),
                            "Notch", 1_700_000_000_000L, 1_763_650_000_000L, 3)));

    /**
     * The scripts that earlier versions ran and this one no longer lists, because their module was
     * removed: the plugin only warns about them, and the history of an older database keeps them.
     * Listing them here makes removing a module a deliberate change, without editing the frozen
     * snapshots. A script that is neither here nor in the plugin's list was renamed, and fails.
     */
    private static final Set<String> RETIRED = Set.of();

    /**
     * What sqlite3 writes for a table that a migration rebuilt (its name in double quotes), and for
     * values that hold backslashes, a line break or a quote: as sqlite3 3.43 wrote it.
     */
    private static final String REBUILT_TABLE_DUMP = """
            PRAGMA foreign_keys=OFF;
            BEGIN TRANSACTION;
            CREATE TABLE IF NOT EXISTS "upgrade_notes" (id VARCHAR(36) PRIMARY KEY, body VARCHAR(200) NOT NULL);
            INSERT INTO upgrade_notes VALUES('path','C:\\new\\path');
            INSERT INTO upgrade_notes VALUES('trailing','ends with \\');
            INSERT INTO upgrade_notes VALUES('lines',replace('é\\n\\','\\n',char(10)));
            INSERT INTO upgrade_notes VALUES('quote','it''s');
            COMMIT;
            """;

    /** The rows of {@link #REBUILT_TABLE_DUMP}, as SQLite holds them. */
    private static final Map<String, String> REBUILT_TABLE_ROWS = Map.of(
            "path", "C:\\new\\path",
            "trailing", "ends with \\",
            "lines", "é\n\\",
            "quote", "it's");

    @TempDir
    Path directory;

    private Database database;
    private final ProfileRepository repository = new ProfileRepository();

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.disable();
        }
    }

    /** A snapshot added without its expected rows would be left out of the tests below. */
    @Test
    void everySnapshotIsChecked() throws IOException, URISyntaxException {
        URL folder = Objects.requireNonNull(DatabaseUpgradeTest.class.getResource("/upgrade"), "upgrade/");
        Set<String> found = new TreeSet<>();
        try (Stream<Path> children = Files.list(Path.of(folder.toURI()))) {
            children.filter(Files::isDirectory).forEach(child -> found.add(child.getFileName().toString()));
        }

        assertEquals(new TreeSet<>(SNAPSHOTS.keySet()), found,
                "Every folder under upgrade/ needs its expected rows in SNAPSHOTS, and every entry a folder");
    }

    @ParameterizedTest(name = "from {0}, on SQLite")
    @MethodSource("snapshots")
    void startsOnTheSqliteDatabaseOfAnEarlierVersion(String snapshot) throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data.db"))) {
            SqlDump.load(connection, dump(snapshot));
        }

        upgradeAndCheck(snapshot);
    }

    @ParameterizedTest(name = "from {0}, on MySQL or MariaDB")
    @MethodSource("snapshots")
    @EnabledIfEnvironmentVariable(named = "FOLIATE_TEST_MYSQL_PORT", matches = "\\d+")
    void startsOnTheMysqlDatabaseOfAnEarlierVersion(String snapshot) throws Exception {
        try (Connection connection = TestMysql.connect()) {
            dropAllTables(connection);
            SqlDump.load(connection, dump(snapshot));
        }
        Files.writeString(directory.resolve("config.yml"), """
                storage:
                  type: MYSQL
                  mysql:
                    host: %s
                    port: %s
                    database: %s
                    username: %s
                    password: "%s"
                """.formatted(TestMysql.HOST, TestMysql.PORT, TestMysql.DATABASE, TestMysql.USER, TestMysql.PASSWORD));

        upgradeAndCheck(snapshot);
    }

    /** A snapshot whose table was rebuilt would otherwise fail only in the CI database job, at release time. */
    @Test
    void aDumpOfARebuiltTableLoadsIntoSqliteAsItWasWritten() throws Exception {
        String url = "jdbc:sqlite:" + directory.resolve("data.db");
        try (Connection connection = DriverManager.getConnection(url)) {
            SqlDump.load(connection, REBUILT_TABLE_DUMP);
        }

        try (Connection connection = DriverManager.getConnection(url)) {
            assertEquals(REBUILT_TABLE_ROWS, notes(connection));
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "FOLIATE_TEST_MYSQL_PORT", matches = "\\d+")
    void aDumpOfARebuiltTableLoadsIntoMysqlAsItWasWritten() throws Exception {
        try (Connection connection = TestMysql.connect()) {
            dropAllTables(connection);
            SqlDump.load(connection, REBUILT_TABLE_DUMP);
        }

        // Read on a connection of its own, in the server's usual mode.
        try (Connection connection = TestMysql.connect()) {
            assertEquals(REBUILT_TABLE_ROWS, notes(connection));
        }
    }

    private static Map<String, String> notes(Connection connection) throws SQLException {
        Map<String, String> notes = new TreeMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT id, body FROM upgrade_notes")) {
            while (rows.next()) {
                notes.put(rows.getString(1), rows.getString(2));
            }
        }
        return notes;
    }

    static Stream<String> snapshots() {
        return SNAPSHOTS.keySet().stream().sorted();
    }

    private void upgradeAndCheck(String snapshot) throws Exception {
        ConfigService<PluginConfig> config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        database = new Database(directory, () -> config.get().storage(), FoliatePlugin.MIGRATIONS, NOPLogger.NOP_LOGGER);

        assertDoesNotThrow(database::enable, "This version does not start on the database left by " + snapshot
                + ": the servers that update from it would lose their storage (see upgrade/README.md)");

        Set<String> history = history();
        Set<String> missing = new TreeSet<>(FoliatePlugin.MIGRATIONS);
        missing.removeAll(history);
        assertEquals(Set.of(), missing, "Migrations of this version not recorded in the database left by " + snapshot);
        Set<String> unknown = new TreeSet<>(history);
        unknown.removeAll(FoliatePlugin.MIGRATIONS);
        unknown.removeAll(RETIRED);
        assertEquals(Set.of(), unknown, "The database left by " + snapshot + " ran migrations that this version"
                + " neither lists nor retires: a renamed script runs again under its new name. Those of a removed"
                + " module go in RETIRED");

        for (PlayerProfile stored : SNAPSHOTS.get(snapshot)) {
            assertEquals(stored, find(stored.id()), "The profile of " + stored.name() + " in " + snapshot);
        }

        PlayerProfile known = SNAPSHOTS.get(snapshot).getFirst();
        PlayerProfile returning = new PlayerProfile(
                known.id(), known.name(), known.firstSeen(), known.lastSeen() + 60_000, known.joins() + 1);
        PlayerProfile newcomer = new PlayerProfile(UUID.randomUUID(), "Newcomer", 1_800_000_000_000L, 1_800_000_000_000L, 1);
        database.call(connection -> {
            repository.save(connection, returning);
            repository.save(connection, newcomer);
            return null;
        });
        assertEquals(returning, find(returning.id()), "A profile of " + snapshot + " saved again");
        assertEquals(newcomer, find(newcomer.id()), "A new profile saved in the database left by " + snapshot);
    }

    private PlayerProfile find(UUID id) throws SQLException {
        return database.call(connection -> repository.find(connection, id)).orElse(null);
    }

    private Set<String> history() throws SQLException {
        return database.call(connection -> {
            Set<String> scripts = new TreeSet<>();
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT script FROM foliate_migrations")) {
                while (rows.next()) {
                    scripts.add(rows.getString(1));
                }
            }
            return scripts;
        });
    }

    private static String dump(String snapshot) throws IOException {
        try (InputStream in = DatabaseUpgradeTest.class.getResourceAsStream("/upgrade/" + snapshot + "/dump.sql")) {
            if (in == null) {
                throw new IOException("upgrade/" + snapshot + "/dump.sql is missing");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The database starts as empty as on a server that never ran the plugin, whatever an earlier
     * test or snapshot left in it: a table the snapshot does not have may be created by a migration.
     */
    private static void dropAllTables(Connection connection) throws SQLException {
        List<String> tables = new ArrayList<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery(
                        "SELECT table_name FROM information_schema.tables WHERE table_schema = DATABASE()")) {
            while (rows.next()) {
                tables.add(rows.getString(1));
            }
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET FOREIGN_KEY_CHECKS = 0");
            for (String table : tables) {
                statement.execute("DROP TABLE `" + table + "`");
            }
            statement.execute("SET FOREIGN_KEY_CHECKS = 1");
        }
    }
}
