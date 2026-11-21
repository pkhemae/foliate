package dev.foliate.modules.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.storage.Database;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
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
            statement.execute("DROP TABLE IF EXISTS foliate_migrations");
        }
        // A session variable set through the driver properties, read back by a test below, and an
        // option the plugin must override (see savingAnUnchangedProfileAgainIsFine).
        Files.writeString(directory.resolve("config.yml"), """
                storage:
                  type: MYSQL
                  mysql:
                    host: %s
                    port: %s
                    database: %s
                    username: %s
                    password: "%s"
                    properties:
                      sessionVariables: "wait_timeout=4321"
                      useAffectedRows: true
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

        assertEquals(1, count("foliate_migrations"));
        assertEquals(0, count("foliate_profiles"));
    }

    /**
     * Several servers of a network share the database and may start together: each must find the
     * migrations applied once, none may fail on the work of another.
     */
    @Test
    void serversStartingTogetherMigrateTheSharedDatabaseOnce() throws Exception {
        for (int round = 0; round < 3; round++) {
            database.disable();
            database = null;
            try (Connection connection = DriverManager.getConnection(url(), USER, PASSWORD);
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP TABLE IF EXISTS foliate_profiles");
                statement.execute("DROP TABLE IF EXISTS foliate_migrations");
            }
            List<Database> servers = IntStream.range(0, 4).mapToObj(i -> newDatabase()).toList();
            ExecutorService starts = Executors.newFixedThreadPool(servers.size());
            try {
                List<Future<?>> started = new ArrayList<>();
                for (Database server : servers) {
                    started.add(starts.submit(() -> {
                        server.enable();
                        return null;
                    }));
                }
                for (Future<?> future : started) {
                    future.get(60, TimeUnit.SECONDS);
                }
            } finally {
                // Every start is over before any server stops, whatever happened; the first one is
                // stopped by tearDown.
                starts.shutdown();
                starts.awaitTermination(60, TimeUnit.SECONDS);
                database = servers.getFirst();
                servers.subList(1, servers.size()).forEach(Database::disable);
            }
            assertEquals(1, count("foliate_migrations"));
        }
    }

    /** Another server inserted the player between our update, which found no row, and our insert. */
    @Test
    void aRowInsertedByAnotherServerInTheMeantimeIsUpdatedInstead() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerProfile theirs = new PlayerProfile(id, "Alex", 5, 20, 1);
        PlayerProfile ours = new PlayerProfile(id, "Alex", 10, 30, 2);

        database.call(connection -> {
            repository.save(racing(connection, theirs), ours);
            return null;
        });

        // Ours, but first seen when the other server saw the player first.
        assertEquals(
                new PlayerProfile(id, "Alex", 5, 30, 2),
                database.call(connection -> repository.find(connection, id)).orElseThrow());
    }

    /**
     * With the driver counting the rows a statement changed rather than the rows it found, an
     * update that changes nothing would look like a missing row, and the insert would fail.
     */
    @Test
    void savingAnUnchangedProfileAgainIsFine() throws Exception {
        PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), "Alex", 10, 20, 1);

        database.call(connection -> {
            repository.save(connection, profile);
            repository.save(connection, profile);
            return null;
        });

        assertEquals(1, count("foliate_profiles"));
    }

    @Test
    void driverPropertiesReachTheDriver() throws Exception {
        int timeout = database.call(connection -> {
            try (Statement statement = connection.createStatement();
                    var rows = statement.executeQuery("SELECT @@session.wait_timeout")) {
                rows.next();
                return rows.getInt(1);
            }
        });

        assertEquals(4321, timeout);
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
        MessageService messages = new MessageService(directory, getClass().getClassLoader()::getResourceAsStream, NOPLogger.NOP_LOGGER);
        messages.enable();
        ProfileModule profiles = new ProfileModule(
                MockBukkit.createMockPlugin(),
                database,
                messages,
                new PlaceholderRegistry(NOPLogger.NOP_LOGGER),
                NOPLogger.NOP_LOGGER,
                Clock.systemUTC());
        profiles.enable();
        UUID id = UUID.randomUUID();

        PlayerMock first = new PlayerMock(server, "Alex", id);
        server.addPlayer(first);
        first.disconnect();
        PlayerMock second = new PlayerMock(server, "Alex", id);
        server.addPlayer(second);
        second.disconnect();
        assertTrue(profiles.awaitWrites(10));

        PlayerProfile stored = database.call(connection -> repository.find(connection, id)).orElseThrow();
        assertEquals(2, stored.joins());
        profiles.disable();
    }

    /**
     * On SQLite the storage has one thread, so the order in which saves are submitted is the order
     * in which they land. Here the pool has several connections: only the per-player queue keeps
     * the saves of one player in order, and the database must end with the last change made. Many
     * players change at once, so that a save landing late is certain to show.
     */
    @Test
    void changesToEachPlayerLandInOrderOnARealPool() throws Exception {
        ServerMock server = MockBukkit.mock();
        MessageService messages = new MessageService(directory, getClass().getClassLoader()::getResourceAsStream, NOPLogger.NOP_LOGGER);
        messages.enable();
        ProfileModule profiles = new ProfileModule(
                MockBukkit.createMockPlugin(), database, messages, new PlaceholderRegistry(NOPLogger.NOP_LOGGER), NOPLogger.NOP_LOGGER, Clock.systemUTC());
        profiles.enable();
        int players = 40;
        int changes = 100;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < players; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            server.addPlayer(new PlayerMock(server, "Player" + i, id));
        }
        ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (UUID id : ids) {
                done.add(pool.submit(() -> {
                    for (int i = 0; i < changes; i++) {
                        profiles.update(id, profile ->
                                new PlayerProfile(profile.id(), profile.name(), profile.firstSeen(), profile.lastSeen(), profile.joins() + 1));
                    }
                }));
            }
            for (Future<?> future : done) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertTrue(profiles.awaitWrites(60));

        for (UUID id : ids) {
            assertEquals(1 + changes, profiles.find(id).orElseThrow().joins());
            assertEquals(1 + changes, database.call(connection -> repository.find(connection, id)).orElseThrow().joins());
        }
        profiles.disable();
    }

    /**
     * A connection on which the first update finds no row because, just before it, another server
     * inserts {@code theirs}: what a save sees when it loses the race for a new player.
     */
    private Connection racing(Connection real, PlayerProfile theirs) {
        AtomicBoolean raced = new AtomicBoolean();
        return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
            Object result = invoke(real, method, arguments);
            if (method.getName().equals("prepareStatement")
                    && ((String) arguments[0]).startsWith("UPDATE")
                    && raced.compareAndSet(false, true)) {
                PreparedStatement update = (PreparedStatement) result;
                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {PreparedStatement.class}, (self, call, parameters) -> {
                    if (call.getName().equals("executeUpdate")) {
                        try (Connection other = DriverManager.getConnection(url(), USER, PASSWORD)) {
                            repository.save(other, theirs);
                        }
                        return 0;
                    }
                    return invoke(update, call, parameters);
                });
            }
            return result;
        });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] arguments) throws Throwable {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private Database newDatabase() {
        return new Database(directory, () -> config.get().storage(), ProfileModule.MIGRATIONS, NOPLogger.NOP_LOGGER);
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
