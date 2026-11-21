package dev.foliate.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.slf4j.Logger;

/**
 * Applies the SQL scripts under {@code migrations/} that the database has not seen yet, in
 * order, each in its own transaction, and records them in a history table.
 *
 * <p>The scripts are given by the caller, in the order they apply: the position in the list, from
 * 1, is the version. To change the schema, add a new script and append its name to that list. Never
 * edit a script that has shipped: servers that already ran it would not run it again. Scripts must
 * stay portable across SQLite and MySQL.
 *
 * <p>Keep each script to one change. MySQL commits schema statements as it runs them, so there a
 * script that fails halfway cannot be rolled back, and it is not recorded as applied either.
 *
 * <p>On MySQL and MariaDB, several servers of a network may share the database and start at the
 * same time: the migrations run under a named lock of the database server, so that one server
 * applies them while the others wait, then find them applied. SQLite is a file of one server.
 *
 * <p>On SQLite, foreign keys are off while the migrations run and checked before each one is
 * committed, as SQLite asks for schema changes: rebuilding a table (create, copy, drop, rename)
 * would otherwise delete the rows that refer to it, and the pragma that prevents it has no effect
 * inside the transaction each script runs in.
 */
final class Migrations {

    private static final String HISTORY_TABLE = "foliate_schema_history";
    /** How long a server waits for another one to finish migrating the shared database. */
    private static final int LOCK_TIMEOUT_SECONDS = 60;
    /**
     * Evaluated by the server: the history table and a hash of the current database's name, cut to
     * MySQL's limit of 64 characters. The hash keeps two long names with a common start apart,
     * which cutting the name itself would not.
     */
    private static final String LOCK_NAME = "LEFT(CONCAT('" + HISTORY_TABLE + ".', SHA1(DATABASE())), 64)";

    private final Logger logger;
    private final List<String> scripts;
    private final StorageSettings.Type type;

    Migrations(Logger logger, List<String> scripts, StorageSettings.Type type) {
        this.logger = logger;
        this.scripts = scripts;
        this.type = type;
    }

    void apply(Connection connection) throws SQLException, IOException {
        if (type == StorageSettings.Type.SQLITE) {
            applyOnSqlite(connection);
            return;
        }
        // The lock belongs to the connection's session: taken and released on this connection.
        // It is named after the database, on the server, so that plugins on other databases of
        // the same MySQL server do not wait for each other.
        try (PreparedStatement take = connection.prepareStatement("SELECT GET_LOCK(" + LOCK_NAME + ", ?)")) {
            take.setInt(1, LOCK_TIMEOUT_SECONDS);
            try (ResultSet result = take.executeQuery()) {
                int taken = result.next() ? result.getInt(1) : 0;
                if (result.wasNull()) {
                    throw new SQLException("The database server could not lock the migrations");
                }
                if (taken != 1) {
                    throw new SQLException("Another server kept the database migrations locked for more than "
                            + LOCK_TIMEOUT_SECONDS + " seconds");
                }
            }
        }
        try {
            applyAll(connection);
        } catch (SQLException | IOException | RuntimeException e) {
            // The migration's failure is the one to report, even when the connection is gone and
            // the release fails too.
            try {
                release(connection);
            } catch (SQLException releaseFailure) {
                e.addSuppressed(releaseFailure);
            }
            throw e;
        }
        release(connection);
    }

    private void applyOnSqlite(Connection connection) throws SQLException, IOException {
        boolean foreignKeys = foreignKeys(connection);
        setForeignKeys(connection, false);
        try {
            applyAll(connection);
        } catch (SQLException | IOException | RuntimeException e) {
            try {
                setForeignKeys(connection, foreignKeys);
            } catch (SQLException restoreFailure) {
                e.addSuppressed(restoreFailure);
            }
            throw e;
        }
        setForeignKeys(connection, foreignKeys);
    }

    private static boolean foreignKeys(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("PRAGMA foreign_keys")) {
            return result.next() && result.getInt(1) == 1;
        }
    }

    private static void setForeignKeys(Connection connection, boolean on) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = " + (on ? "ON" : "OFF"));
        }
    }

    /** With foreign keys off, a script could leave rows that refer to nothing: refused here. */
    private static void checkForeignKeys(Connection connection, String script) throws SQLException {
        try (Statement statement = connection.createStatement();
                ResultSet broken = statement.executeQuery("PRAGMA foreign_key_check")) {
            if (broken.next()) {
                throw new SQLException("Database migration " + script + " leaves rows of " + broken.getString(1)
                        + " that refer to missing rows of " + broken.getString(3));
            }
        }
    }

    /**
     * A failure here fails the start of the database, whose pool is then closed: closing the
     * connection releases the lock anyway, so it never stays on a pooled connection.
     */
    private static void release(Connection connection) throws SQLException {
        try (PreparedStatement release = connection.prepareStatement("SELECT RELEASE_LOCK(" + LOCK_NAME + ")");
                ResultSet result = release.executeQuery()) {
            if (!result.next() || result.getInt(1) != 1) {
                throw new SQLException("The migrations lock was not held when it was released");
            }
        }
    }

    private void applyAll(Connection connection) throws SQLException, IOException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        Exception failure = null;
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE
                        + " (version INTEGER PRIMARY KEY, script VARCHAR(200) NOT NULL, applied_at BIGINT NOT NULL)");
            }
            connection.commit();

            Map<Integer, String> applied = appliedScripts(connection);
            checkHistory(applied);
            for (int version = 1; version <= scripts.size(); version++) {
                if (applied.containsKey(version)) {
                    continue;
                }
                String script = scripts.get(version - 1);
                try {
                    run(connection, script);
                    if (type == StorageSettings.Type.SQLITE) {
                        checkForeignKeys(connection, script);
                    }
                    record(connection, version, script);
                    connection.commit();
                    logger.info("Applied database migration {}", script);
                } catch (SQLException | IOException e) {
                    // The migration's failure is the one to report: the connection may be gone,
                    // and then the rollback fails too.
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        e.addSuppressed(rollbackFailure);
                    }
                    throw e;
                }
            }
        } catch (SQLException | IOException | RuntimeException e) {
            failure = e;
            throw e;
        } finally {
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException restoreFailure) {
                if (failure == null) {
                    throw restoreFailure;
                }
                failure.addSuppressed(restoreFailure);
            }
        }
    }

    /**
     * A version recorded under another script's name means the list of scripts was reordered or a
     * script renamed: running the rest would apply them on top of a schema they do not expect.
     */
    private void checkHistory(Map<Integer, String> applied) throws SQLException {
        for (Map.Entry<Integer, String> recorded : applied.entrySet()) {
            int version = recorded.getKey();
            if (version > scripts.size()) {
                logger.warn("The database has migration {} ({}), which this version of the plugin does not know:"
                        + " it was used by a newer version", version, recorded.getValue());
            } else if (!scripts.get(version - 1).equals(recorded.getValue())) {
                throw new SQLException("Database migration " + version + " was applied as " + recorded.getValue()
                        + " but this version of the plugin names it " + scripts.get(version - 1)
                        + ": shipped migrations must never be renamed or reordered");
            }
        }
    }

    private static Map<Integer, String> appliedScripts(Connection connection) throws SQLException {
        Map<Integer, String> scripts = new TreeMap<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT version, script FROM " + HISTORY_TABLE)) {
            while (rows.next()) {
                scripts.put(rows.getInt(1), rows.getString(2));
            }
        }
        return scripts;
    }

    private static void run(Connection connection, String script) throws SQLException, IOException {
        String sql;
        try (InputStream in = Migrations.class.getResourceAsStream("/migrations/" + script)) {
            if (in == null) {
                throw new IOException("Migration " + script + " is missing from the plugin jar");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement statement = connection.createStatement()) {
            for (String single : statements(sql)) {
                statement.execute(single);
            }
        }
    }

    /**
     * Splits a script into statements, on the semicolons that are not quoted. Outside quotes,
     * {@code --} starts a comment that runs to the end of the line, and <code>/* ... *&#47;</code> is a
     * comment too. A quote inside a value is escaped by doubling it, the one way both SQLite and
     * MySQL understand. Stored procedures and triggers, whose bodies hold semicolons of their own,
     * are not supported.
     */
    static List<String> statements(String script) {
        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (quote != 0) {
                current.append(c);
                // A doubled quote is an escaped one, and the value goes on.
                if (c == quote) {
                    if (i + 1 < script.length() && script.charAt(i + 1) == quote) {
                        current.append(script.charAt(++i));
                    } else {
                        quote = 0;
                    }
                }
            } else if (c == '\'' || c == '"' || c == '`') {
                quote = c;
                current.append(c);
            } else if (c == '-' && i + 1 < script.length() && script.charAt(i + 1) == '-') {
                while (i + 1 < script.length() && script.charAt(i + 1) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < script.length() && script.charAt(i + 1) == '*') {
                int end = script.indexOf("*/", i + 2);
                i = end < 0 ? script.length() : end + 1;
                // Kept apart from what surrounds it, as the comment did.
                current.append(' ');
            } else if (c == ';') {
                add(statements, current);
            } else {
                current.append(c);
            }
        }
        add(statements, current);
        return List.copyOf(statements);
    }

    private static void add(List<String> statements, StringBuilder statement) {
        String stripped = statement.toString().strip();
        if (!stripped.isEmpty()) {
            statements.add(stripped);
        }
        statement.setLength(0);
    }

    private static void record(Connection connection, int version, String script) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + HISTORY_TABLE + " (version, script, applied_at) VALUES (?, ?, ?)")) {
            insert.setInt(1, version);
            insert.setString(2, script);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }
}
