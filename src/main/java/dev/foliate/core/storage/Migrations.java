package dev.foliate.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;

/**
 * Applies the SQL scripts under {@code db/migration/} that the database has not seen yet, in
 * order, each in its own transaction, and records them in a history table.
 *
 * <p>To change the schema, add a new script and append its name to {@link #SCRIPTS}. Never edit
 * a script that has shipped: servers that already ran it would not run it again. Scripts must stay
 * portable across SQLite and MySQL.
 */
final class Migrations {

    /** In the order they are applied. The position in this list, from 1, is the version. */
    private static final List<String> SCRIPTS = List.of("V1__create_profiles.sql");

    private static final String HISTORY_TABLE = "foliate_schema_history";

    private final Logger logger;
    private final List<String> scripts;

    Migrations(Logger logger) {
        this(logger, SCRIPTS);
    }

    Migrations(Logger logger, List<String> scripts) {
        this.logger = logger;
        this.scripts = scripts;
    }

    void apply(Connection connection) throws SQLException, IOException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS " + HISTORY_TABLE
                        + " (version INTEGER PRIMARY KEY, script VARCHAR(200) NOT NULL, applied_at BIGINT NOT NULL)");
            }
            connection.commit();

            Set<Integer> applied = appliedVersions(connection);
            for (int version = 1; version <= scripts.size(); version++) {
                if (applied.contains(version)) {
                    continue;
                }
                String script = scripts.get(version - 1);
                try {
                    run(connection, script);
                    record(connection, version, script);
                    connection.commit();
                    logger.info("Applied database migration {}", script);
                } catch (SQLException | IOException e) {
                    connection.rollback();
                    throw e;
                }
            }
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static Set<Integer> appliedVersions(Connection connection) throws SQLException {
        Set<Integer> versions = new HashSet<>();
        try (Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT version FROM " + HISTORY_TABLE)) {
            while (rows.next()) {
                versions.add(rows.getInt(1));
            }
        }
        return versions;
    }

    private static void run(Connection connection, String script) throws SQLException, IOException {
        String sql;
        try (InputStream in = Migrations.class.getResourceAsStream("/db/migration/" + script)) {
            if (in == null) {
                throw new IOException("Migration " + script + " is missing from the plugin jar");
            }
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (Statement statement = connection.createStatement()) {
            // Statements end with a semicolon at the end of a line; scripts hold no procedures.
            for (String part : sql.split(";\\s*(\\r?\\n|$)")) {
                String single = part.strip();
                if (!single.isEmpty()) {
                    statement.execute(single);
                }
            }
        }
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
