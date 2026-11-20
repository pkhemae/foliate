package dev.foliate.modules.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.storage.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * The storage of profiles on a real MySQL or MariaDB server, through the same {@link Database} and
 * migrations as the plugin. It runs only where such a server is given by the environment: CI
 * starts both, and {@code FOLIATE_TEST_MYSQL_PORT} is what turns it on. The other variables
 * default to a local server whose root account has no password, and to a database named
 * {@code foliate_test}. The plugin's tables are dropped from that database first: point it at a
 * throwaway one, never at the data of a real server.
 */
@EnabledIfEnvironmentVariable(named = "FOLIATE_TEST_MYSQL_PORT", matches = "\\d+")
class ProfileStorageOnMysqlTest {

    private static final String HOST = env("FOLIATE_TEST_MYSQL_HOST", "127.0.0.1");
    private static final String PORT = env("FOLIATE_TEST_MYSQL_PORT", "3306");
    private static final String DATABASE = env("FOLIATE_TEST_MYSQL_DATABASE", "foliate_test");
    private static final String USER = env("FOLIATE_TEST_MYSQL_USER", "root");
    private static final String PASSWORD = env("FOLIATE_TEST_MYSQL_PASSWORD", "");

    @TempDir
    Path directory;

    private ConfigService<PluginConfig> config;
    private Database database;
    private final ProfileRepository repository = new ProfileRepository();

    @BeforeEach
    void setUp() throws Exception {
        try (Connection connection = DriverManager.getConnection(url(), USER, PASSWORD);
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS foliate_profiles");
            statement.execute("DROP TABLE IF EXISTS foliate_schema_history");
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
                """.formatted(HOST, PORT, DATABASE, USER, PASSWORD));
        config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        database = newDatabase();
        database.enable();
    }

    @AfterEach
    void tearDown() {
        if (database != null) {
            database.disable();
        }
        MockBukkit.unmock();
    }

    @Test
    void theMigrationsRunOnceWhateverTheNumberOfStarts() throws Exception {
        database.disable();
        database = newDatabase();
        database.enable();

        assertEquals(1, count("foliate_schema_history"));
        assertEquals(0, count("foliate_profiles"));
    }

    @Test
    void aProfileComesBackAsItWasSaved() throws Exception {
        // The longest name the column must hold, and an epoch in milliseconds, which needs BIGINT.
        String name = "x".repeat(32);
        PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), name, 1_900_000_000_000L, 1_900_000_100_000L, 3);

        database.call(connection -> {
            repository.save(connection, profile);
            return null;
        });

        assertEquals(profile, database.call(connection -> repository.find(connection, profile.id())).orElseThrow());
    }

    @Test
    void savingAgainUpdatesTheRowInsteadOfAddingOne() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerProfile first = new PlayerProfile(id, "Alex", 10, 20, 1);

        database.call(connection -> {
            repository.save(connection, first);
            repository.save(connection, new PlayerProfile(id, "Alex2", 10, 30, 2));
            return null;
        });

        assertEquals(1, count("foliate_profiles"));
        PlayerProfile stored = database.call(connection -> repository.find(connection, id)).orElseThrow();
        assertEquals(new PlayerProfile(id, "Alex2", 10, 30, 2), stored);
    }

    @Test
    void manyPlayersCanBeSavedAtTheSameTime() throws Exception {
        CompletableFuture<?>[] saves = IntStream.range(0, 50)
                .mapToObj(i -> database.submit(connection -> {
                    repository.save(connection, new PlayerProfile(UUID.randomUUID(), "Player" + i, i, i, 1));
                    return null;
                }))
                .toArray(CompletableFuture[]::new);

        CompletableFuture.allOf(saves).get();

        assertEquals(50, count("foliate_profiles"));
    }

    @Test
    void aPlayerWhoJoinsTwiceIsCountedTwice() throws Exception {
        ServerMock server = MockBukkit.mock();
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);
        messages.enable();
        ProfileModule profiles = new ProfileModule(
                MockBukkit.createMockPlugin(),
                database,
                messages,
                new PlaceholderRegistry(),
                NOPLogger.NOP_LOGGER,
                Clock.systemUTC());
        profiles.enable();
        UUID id = UUID.randomUUID();

        server.addPlayer(new PlayerMock(server, "Alex", id));
        profiles.quit(id);
        server.addPlayer(new PlayerMock(server, "Alex", id));
        profiles.quit(id);
        assertTrue(profiles.awaitWrites(10));

        PlayerProfile stored = database.call(connection -> repository.find(connection, id)).orElseThrow();
        assertEquals(2, stored.joins());
        profiles.disable();
    }

    private Database newDatabase() {
        return new Database(directory, () -> config.get().storage(), List.of(ProfileModule.MIGRATION), NOPLogger.NOP_LOGGER);
    }

    private int count(String table) throws SQLException {
        return database.call(connection -> {
            try (Statement statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }

    private static String url() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
