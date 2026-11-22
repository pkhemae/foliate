package dev.foliate.modules.profile.storage;

import dev.foliate.core.player.PlayerRepository;
import dev.foliate.modules.profile.PlayerProfile;
import dev.foliate.core.storage.SqlErrors;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * SQL for profiles. Plain JDBC, written to run unchanged on SQLite and MySQL.
 *
 * <p>A save writes the whole row as the server last saw it. When servers of a network share a MySQL
 * database, a player who switches servers can be loaded by the next one before the previous one
 * saved their quit: the Storage page of the wiki describes this limit and how to lift it.
 */
public final class ProfileRepository implements PlayerRepository<PlayerProfile> {

    @Override
    public Optional<PlayerProfile> find(Connection connection, UUID id) throws SQLException {
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT name, first_seen, last_seen, joins FROM foliate_profiles WHERE id = ?")) {
            select.setString(1, id.toString());
            try (ResultSet row = select.executeQuery()) {
                if (!row.next()) {
                    return Optional.empty();
                }
                return Optional.of(new PlayerProfile(
                        id, row.getString("name"), row.getLong("first_seen"), row.getLong("last_seen"), row.getInt("joins")));
            }
        }
    }

    /**
     * Inserts or updates. Update first: "upsert" syntax differs between SQLite and MySQL. When
     * another server sharing the database inserted the row between the two, the insert fails on
     * the key and the update is made again, so the save is not lost; {@code first_seen} stays the
     * one that server wrote.
     */
    @Override
    public void save(Connection connection, PlayerProfile profile) throws SQLException {
        if (update(connection, profile)) {
            return;
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO foliate_profiles (id, name, first_seen, last_seen, joins) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, profile.id().toString());
            insert.setString(2, profile.name());
            insert.setLong(3, profile.firstSeen());
            insert.setLong(4, profile.lastSeen());
            insert.setInt(5, profile.joins());
            insert.executeUpdate();
        } catch (SQLException e) {
            if (!SqlErrors.isDuplicateKey(e)) {
                throw e;
            }
            boolean updated;
            try {
                updated = update(connection, profile);
            } catch (SQLException retry) {
                retry.addSuppressed(e);
                throw retry;
            }
            if (!updated) {
                throw e;
            }
        }
    }

    /** @return false when there is no row to update */
    private static boolean update(Connection connection, PlayerProfile profile) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE foliate_profiles SET name = ?, last_seen = ?, joins = ? WHERE id = ?")) {
            update.setString(1, profile.name());
            update.setLong(2, profile.lastSeen());
            update.setInt(3, profile.joins());
            update.setString(4, profile.id().toString());
            return update.executeUpdate() > 0;
        }
    }
}
