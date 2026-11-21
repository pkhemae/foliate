package dev.foliate.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.testing.RecordingLogger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class MigrationsTest {

    @TempDir
    Path directory;

    @Test
    void statementsAreSplitOnSemicolons() {
        assertEquals(
                List.of("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)"),
                Migrations.statements("CREATE TABLE a (id INT);\nCREATE TABLE b (id INT);\n"));
    }

    /** A comment after a statement used to swallow the statement that followed it. */
    @Test
    void commentsDoNotHideTheNextStatement() {
        String script = """
                -- first table
                CREATE TABLE a (id INT); -- trailing comment
                CREATE TABLE b (
                    id INT -- the key
                );
                """;

        assertEquals(
                List.of("CREATE TABLE a (id INT)", "CREATE TABLE b (\n    id INT \n)"),
                Migrations.statements(script));
    }

    /** A quoted semicolon or dashes are part of the value, not the end of a statement. */
    @Test
    void quotedSemicolonsAndDashesStayInTheirValue() {
        String script = """
                INSERT INTO t VALUES ('a;b', 'x--y', 'it''s; fine');
                INSERT INTO "odd;name" (`col--1`) VALUES (1); -- done
                """;

        assertEquals(
                List.of(
                        "INSERT INTO t VALUES ('a;b', 'x--y', 'it''s; fine')",
                        "INSERT INTO \"odd;name\" (`col--1`) VALUES (1)"),
                Migrations.statements(script));
    }

    /** Block comments are skipped like line comments, quotes in them included. */
    @Test
    void blockCommentsAreNotStatements() {
        assertEquals(
                List.of("CREATE TABLE homes (id INT)", "CREATE TABLE warps (id INT)"),
                Migrations.statements("/* Players' homes */\nCREATE TABLE homes (id INT);\nCREATE TABLE warps /* and; */ (id INT);\n")
                        .stream().map(statement -> statement.replaceAll("\\s+", " ")).toList());
    }

    /**
     * Rebuilding a table is how SQLite changes a column: with foreign keys on, dropping the old
     * table would delete the rows that refer to it.
     */
    @Test
    void rebuildingATableKeepsTheRowsThatReferToIt() throws Exception {
        try (Connection connection = sqlite()) {
            new Migrations(NOPLogger.NOP_LOGGER, List.of("003_parents.sql"), StorageSettings.Type.SQLITE).apply(connection);
            execute(connection, "INSERT INTO parents (id, name) VALUES (1, 'a')");
            execute(connection, "INSERT INTO children (id, parent) VALUES (10, 1)");

            new Migrations(NOPLogger.NOP_LOGGER, List.of("003_parents.sql", "004_rebuild_parents.sql"), StorageSettings.Type.SQLITE)
                    .apply(connection);

            assertEquals(1, count(connection, "children"));
            assertEquals(1, count(connection, "parents"));
            assertEquals(1, count(connection, "pragma_foreign_keys WHERE foreign_keys = 1"), "foreign keys were not turned back on");
        }
    }

    /** With foreign keys off during a migration, what it breaks is checked before it is committed. */
    @Test
    void aMigrationThatBreaksAForeignKeyIsRefused() throws Exception {
        try (Connection connection = sqlite()) {
            new Migrations(NOPLogger.NOP_LOGGER, List.of("003_parents.sql"), StorageSettings.Type.SQLITE).apply(connection);
            execute(connection, "INSERT INTO parents (id, name) VALUES (1, 'a')");
            execute(connection, "INSERT INTO children (id, parent) VALUES (10, 1)");

            SQLException failure = assertThrows(SQLException.class, () -> new Migrations(
                            NOPLogger.NOP_LOGGER, List.of("003_parents.sql", "005_orphan_children.sql"), StorageSettings.Type.SQLITE)
                    .apply(connection));

            assertTrue(failure.getMessage().contains("005_orphan_children.sql"), failure.getMessage());
            assertEquals(1, count(connection, "parents"), "the migration was not rolled back");
        }
    }

    /** A connection as the database gives them: with foreign keys on. */
    private Connection sqlite() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("foreign_keys", "true");
        return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("test.db"), properties);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int count(Connection connection, String table) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    /** The migration's own error is the one to report, even when the rollback fails too. */
    @Test
    void aFailedRollbackDoesNotHideTheMigrationsError() throws Exception {
        try (Connection real = sqlite()) {
            Connection broken = lost(real);
            Migrations migrations = new Migrations(
                    NOPLogger.NOP_LOGGER, List.of("001_create_notes.sql", "002_broken.sql"), StorageSettings.Type.SQLITE);

            SQLException failure = assertThrows(SQLException.class, () -> migrations.apply(broken));

            assertTrue(failure.getMessage().contains("incomplete input"), failure.getMessage());
            assertEquals(
                    List.of("rollback failed", "setAutoCommit failed"),
                    Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList());
        }
    }

    /**
     * Two modules each own their scripts. When the first appends one, the second's must neither
     * move nor run again: scripts are known by name, not by their position in the plugin's list.
     */
    @Test
    void aModuleThatAddsAScriptLeavesTheOthersAlone() throws Exception {
        List<String> notes = List.of("001_create_notes.sql");
        List<String> visits = List.of("001_create_visits.sql");
        try (Connection connection = sqlite()) {
            new Migrations(NOPLogger.NOP_LOGGER, concat(notes, visits), StorageSettings.Type.SQLITE).apply(connection);

            List<String> notesThen = concat(notes, List.of("003_parents.sql"));
            new Migrations(NOPLogger.NOP_LOGGER, concat(notesThen, visits), StorageSettings.Type.SQLITE).apply(connection);
            // The order of the modules does not matter either.
            new Migrations(NOPLogger.NOP_LOGGER, concat(visits, notesThen), StorageSettings.Type.SQLITE).apply(connection);

            assertEquals(0, count(connection, "parents"));
            assertEquals(3, count(connection, "foliate_migrations"));
        }
    }

    /** Renamed, or left behind by a module that was removed: worth a line, not a failed start. */
    @Test
    void aScriptTheDatabaseRanButNoModuleListsIsReported() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        try (Connection connection = sqlite()) {
            new Migrations(NOPLogger.NOP_LOGGER, List.of("001_create_notes.sql"), StorageSettings.Type.SQLITE).apply(connection);

            new Migrations(logger, List.of("001_create_visits.sql"), StorageSettings.Type.SQLITE).apply(connection);
        }

        assertTrue(
                logger.lines().stream().anyMatch(line -> line.startsWith("WARN The database ran the migration 001_create_notes.sql")),
                logger.lines().toString());
    }

    /** The history knows scripts by name: the second one would never run. */
    @Test
    void aScriptListedTwiceIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Migrations(
                NOPLogger.NOP_LOGGER, List.of("001_create_notes.sql", "001_create_notes.sql"), StorageSettings.Type.SQLITE));
    }

    private static List<String> concat(List<String> first, List<String> second) {
        return Stream.concat(first.stream(), second.stream()).toList();
    }

    /**
     * The connection as if it dropped after the failure: the rollback fails, and so does turning
     * auto-commit back on.
     */
    private static Connection lost(Connection real) {
        return (Connection) Proxy.newProxyInstance(
                MigrationsTest.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("rollback")
                            || (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0]))) {
                        throw new SQLException(method.getName() + " failed");
                    }
                    try {
                        return method.invoke(real, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }

    @Test
    void blankScriptHasNoStatement() {
        assertEquals(List.of(), Migrations.statements("\n-- nothing here\n;\n"));
    }
}
