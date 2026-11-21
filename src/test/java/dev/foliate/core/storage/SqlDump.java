package dev.foliate.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Loads a dump written by {@code sqlite3 data.db .dump}, for the tests that start from the
 * database an earlier version left. The statements are split as the migrations are.
 *
 * <p>The same dump loads into MySQL and MariaDB, read the way SQLite wrote it: names in double
 * quotes, which sqlite3 writes for a table that was rebuilt or renamed, are names and not strings,
 * and a backslash in a value is a backslash, not an escape.
 */
public final class SqlDump {

    /**
     * What sqlite3 writes around the tables, which MySQL does not understand: skipped, so that the
     * dump is kept as sqlite3 wrote it. Each statement then commits on its own.
     */
    private static final Pattern SQLITE_FRAME = Pattern.compile("(PRAGMA|BEGIN TRANSACTION|COMMIT)\\b.*", Pattern.DOTALL);

    private SqlDump() {}

    /**
     * On MySQL and MariaDB, this changes the SQL mode of the connection's session: give it a
     * connection of its own, not one of the plugin's pool.
     */
    public static void load(Connection connection, String dump) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            if (!connection.getMetaData().getDatabaseProductName().equalsIgnoreCase("SQLite")) {
                statement.execute("SET SESSION sql_mode = CONCAT_WS(',', NULLIF(@@sql_mode, ''),"
                        + " 'ANSI_QUOTES', 'NO_BACKSLASH_ESCAPES')");
            }
            for (String sql : Migrations.statements(dump)) {
                if (!SQLITE_FRAME.matcher(sql.toUpperCase(Locale.ROOT)).matches()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
