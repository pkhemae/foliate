package dev.foliate.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
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

    /** The migration's own error is the one to report, even when the rollback fails too. */
    @Test
    void aFailedRollbackDoesNotHideTheMigrationsError() throws Exception {
        try (Connection real = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("test.db"))) {
            Connection broken = lost(real);
            Migrations migrations = new Migrations(
                    NOPLogger.NOP_LOGGER, List.of("001_create_notes.sql", "002_broken.sql"), false);

            SQLException failure = assertThrows(SQLException.class, () -> migrations.apply(broken));

            assertTrue(failure.getMessage().contains("incomplete input"), failure.getMessage());
            assertEquals(
                    List.of("rollback failed", "setAutoCommit failed"),
                    Arrays.stream(failure.getSuppressed()).map(Throwable::getMessage).toList());
        }
    }

    /** Running the rest on a schema they do not expect could damage it. */
    @Test
    void aRenamedOrReorderedMigrationStopsTheStart() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("test.db"))) {
            new Migrations(NOPLogger.NOP_LOGGER, List.of("001_create_notes.sql"), false).apply(connection);

            SQLException failure = assertThrows(SQLException.class, () ->
                    new Migrations(NOPLogger.NOP_LOGGER, List.of("001_create_visits.sql"), false).apply(connection));

            assertTrue(failure.getMessage().contains("renamed or reordered"), failure.getMessage());
        }
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
