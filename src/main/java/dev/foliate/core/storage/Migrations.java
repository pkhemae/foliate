package dev.foliate.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;

/**
 * Applies the SQL scripts under {@code migrations/} that the database has not seen yet, in
 * order, each in its own transaction, and records them in a history table.
 *
 * <p>To change the schema, add a new script and append its name to {@link #SCRIPTS}. Never edit
 * a script that has shipped: servers that already ran it would not run it again. Scripts must stay
 * portable across SQLite and MySQL.
 *
 * <p>Keep each script to one change. MySQL commits schema statements as it runs them, so there a
 * script that fails halfway cannot be rolled back, and it is not recorded as applied either.
 */
final class Migrations {

    /** In the order they are applied. The position in this list, from 1, is the version. */
    private static final List<String> SCRIPTS = List.of("001_create_profiles.sql");

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
     * Splits a script into statements. Deliberately simple: comments start with {@code --} and run
     * to the end of the line, and statements are separated by semicolons. A script must therefore
     * not contain {@code --} or a semicolon inside a quoted value, nor stored procedures.
     */
    static List<String> statements(String script) {
        String withoutComments = script.lines()
                .map(line -> {
                    int comment = line.indexOf("--");
                    return comment < 0 ? line : line.substring(0, comment);
                })
                .collect(Collectors.joining("\n"));
        return Arrays.stream(withoutComments.split(";"))
                .map(String::strip)
                .filter(statement -> !statement.isEmpty())
                .toList();
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
