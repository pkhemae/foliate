package dev.foliate.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Loads a dump written by {@code sqlite3 data.db .dump}, for the tests that start from the
 * database an earlier version left. The statements are split as the migrations are.
 */
public final class SqlDump {

    /**
     * What sqlite3 writes around the tables, which MySQL does not understand: skipped, so that the
     * dump is kept as sqlite3 wrote it. Each statement then commits on its own.
     */
    private static final Pattern SQLITE_FRAME = Pattern.compile("(PRAGMA|BEGIN TRANSACTION|COMMIT)\\b.*", Pattern.DOTALL);

    private SqlDump() {}

    public static void load(Connection connection, String dump) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            for (String sql : Migrations.statements(dump)) {
                if (!SQLITE_FRAME.matcher(sql.toUpperCase(Locale.ROOT)).matches()) {
                    statement.execute(sql);
                }
            }
        }
    }
}
