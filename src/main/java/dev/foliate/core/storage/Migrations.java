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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;

/**
 * Applies the SQL scripts under {@code migrations/} that the database has not run yet, each in its
 * own transaction, and records them by name in a history table.
 *
 * <p>A script is known by its name, such as {@code profile/001_create_profiles.sql}: each module
 * keeps its scripts in a folder of its own and lists them in the order they apply, appending new
 * ones at the end. The lists of the modules are given together, in any order, since a module never
 * uses another one's tables: what one module adds never moves another one's scripts, and only the
 * scripts missing from the history run, in the order of their list. Never edit or rename a script
 * that has shipped: servers that already ran it would not run it again, or would run it twice.
 * Scripts must stay portable across SQLite and MySQL.
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

    private static final String HISTORY_TABLE = "foliate_migrations";
    /** The longest name a key of MySQL can hold in any row format: 191 characters of 4 bytes. */
    private static final int MAX_NAME_LENGTH = 191;
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

    /**
     * @param scripts the names of the scripts, each module's in the order they apply
     * @throws IllegalArgumentException when two scripts have the same name: the second would never
     *     run, since the history knows them by name
     */
    Migrations(Logger logger, List<String> scripts, StorageSettings.Type type) {
        requireUniqueNames(scripts);
        this.logger = logger;
        this.scripts = List.copyOf(scripts);
        this.type = type;
    }

    /**
     * @throws IllegalArgumentException when two scripts have the same name, ignoring case as MySQL
     *     compares them, or a name is longer than the history can record
     */
    static void requireUniqueNames(List<String> scripts) {
        Set<String> names = new HashSet<>();
        for (String script : scripts) {
            if (script.length() > MAX_NAME_LENGTH) {
                throw new IllegalArgumentException(
                        "The migration " + script + " has a name longer than " + MAX_NAME_LENGTH + " characters");
            }
            if (!names.add(script.toLowerCase(Locale.ROOT))) {
                throw new IllegalArgumentException("The migration " + script + " is listed twice");
            }
        }
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
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE
                        + " (script VARCHAR(" + MAX_NAME_LENGTH + ") PRIMARY KEY, applied_at BIGINT NOT NULL)");
            }
            connection.commit();

            Set<String> applied = appliedScripts(connection);
            reportUnknown(applied);
            requireAppendedOnly(applied);
            for (String script : scripts) {
                if (applied.contains(script)) {
                    continue;
                }
                try {
                    run(connection, script);
                    if (type == StorageSettings.Type.SQLITE) {
                        checkForeignKeys(connection, script);
                    }
                    record(connection, script);
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
        } catch (Throwable e) {
            // The migration's failure is the one to report, even when restoring fails too. Any
            // failure, an Error included, must not leave the connection outside auto-commit.
            try {
                connection.setAutoCommit(autoCommit);
            } catch (SQLException restoreFailure) {
                e.addSuppressed(restoreFailure);
            }
            throw e;
        }
        connection.setAutoCommit(autoCommit);
    }

    /**
     * A script the database ran that no module lists any more: renamed (it would then run again
     * under its new name), removed with its module, or listed by a newer version of the plugin.
     */
    private void reportUnknown(Set<String> applied) {
        Set<String> unknown = new TreeSet<>(applied);
        unknown.removeAll(scripts);
        for (String script : unknown) {
            logger.warn("The database ran the migration {}, which this version of the plugin does not list:"
                    + " it was renamed (and runs again under its new name), its module was removed,"
                    + " or the plugin was downgraded", script);
        }
    }

    /**
     * A script missing from the history before one of the same module that already ran was
     * inserted instead of appended: it would run after the later one here, and before it on a new
     * database, and the two would end up with different schemas.
     */
    private void requireAppendedOnly(Set<String> applied) throws SQLException {
        Map<String, String> missingByModule = new HashMap<>();
        for (String script : scripts) {
            String module = moduleOf(script);
            if (!applied.contains(script)) {
                missingByModule.putIfAbsent(module, script);
            } else if (missingByModule.containsKey(module)) {
                throw new SQLException("Database migration " + missingByModule.get(module) + " is listed before "
                        + script + ", which already ran: append new migrations at the end of their module's list");
            }
        }
    }

    /** The folder of a script, which is its module's: {@code profile} for {@code profile/001_x.sql}. */
    private static String moduleOf(String script) {
        int slash = script.lastIndexOf('/');
        return slash < 0 ? "" : script.substring(0, slash);
    }

    private static Set<String> appliedScripts(Connection connection) throws SQLException {
        Set<String> scripts = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT script FROM " + HISTORY_TABLE)) {
            while (rows.next()) {
                scripts.add(rows.getString(1));
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
        List<String> statements;
        try {
            statements = statements(sql);
        } catch (IllegalArgumentException e) {
            throw new IOException("Migration " + script + " cannot be read: " + e.getMessage(), e);
        }
        try (Statement statement = connection.createStatement()) {
            for (String single : statements) {
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
     *
     * @throws IllegalArgumentException when a comment is not closed: everything after it would be
     *     skipped, and the script still recorded as applied
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
                if (end < 0) {
                    throw new IllegalArgumentException("a /* comment is never closed");
                }
                i = end + 1;
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

    private static void record(Connection connection, String script) throws SQLException {
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + HISTORY_TABLE + " (script, applied_at) VALUES (?, ?)")) {
            insert.setString(1, script);
            insert.setLong(2, System.currentTimeMillis());
            insert.executeUpdate();
        }
    }
}
