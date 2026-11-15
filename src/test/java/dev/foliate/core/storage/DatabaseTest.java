package dev.foliate.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.module.ModuleSkippedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class DatabaseTest {

    @TempDir
    Path directory;

    private ConfigService config;
    private Database database;

    @BeforeEach
    void setUp() throws Exception {
        config = new ConfigService(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        database = new Database(directory, config, NOPLogger.NOP_LOGGER);
    }

    @AfterEach
    void tearDown() {
        database.disable();
    }

    @Test
    void enablingCreatesTheSchema() throws Exception {
        database.enable();

        assertTrue(database.isRunning());
        assertEquals(0, countRows("foliate_profiles"));
        assertEquals(1, countRows("foliate_schema_history"));
    }

    @Test
    void migrationsAreAppliedOnlyOnce() throws Exception {
        database.enable();
        database.disable();

        database = new Database(directory, config, NOPLogger.NOP_LOGGER);
        database.enable();

        assertEquals(1, countRows("foliate_schema_history"));
    }

    @Test
    void submitRunsOffTheCallingThread() throws Exception {
        database.enable();

        String thread = database.submit(connection -> Thread.currentThread().getName()).get(5, TimeUnit.SECONDS);

        assertTrue(thread.startsWith("Foliate-Storage-"), thread);
    }

    @Test
    void submitReportsSqlErrorsThroughTheFuture() throws Exception {
        database.enable();

        CompletableFuture<Object> future = database.submit(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.execute("SELECT * FROM no_such_table");
            }
        });

        ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(5, TimeUnit.SECONDS));
        assertInstanceOf(SQLException.class, failure.getCause());
    }

    @Test
    void disableWaitsForQueuedWrites() throws Exception {
        database.enable();
        CompletableFuture<Integer> write = database.submit(connection -> {
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate("INSERT INTO foliate_profiles VALUES ('id', 'name', 1, 1, 1)");
            }
        });

        database.disable();

        assertTrue(write.isDone());
        assertEquals(1, write.get());
    }

    @Test
    void submitAfterDisableFailsInsteadOfHanging() throws Exception {
        database.enable();
        database.disable();

        CompletableFuture<Integer> future = database.submit(connection -> 1);

        assertTrue(future.isCompletedExceptionally());
    }

    @Test
    void storageTurnedOffInConfigurationIsSkipped() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "storage:\n  enabled: false\n");
        config.reload();

        assertThrows(ModuleSkippedException.class, database::enable);
        assertFalse(database.isRunning());
    }

    private int countRows(String table) throws SQLException {
        return database.call(connection -> {
            try (Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next();
                return rows.getInt(1);
            }
        });
    }
}
