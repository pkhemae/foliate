package dev.foliate.profile;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/** SQL for profiles. Plain JDBC, written to run unchanged on SQLite and MySQL. */
final class ProfileRepository {

    Optional<PlayerProfile> find(Connection connection, UUID id) throws SQLException {
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

    /** Inserts or updates. Update first: "upsert" syntax differs between SQLite and MySQL. */
    void save(Connection connection, PlayerProfile profile) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE foliate_profiles SET name = ?, last_seen = ?, joins = ? WHERE id = ?")) {
            update.setString(1, profile.name());
            update.setLong(2, profile.lastSeen());
            update.setInt(3, profile.joins());
            update.setString(4, profile.id().toString());
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO foliate_profiles (id, name, first_seen, last_seen, joins) VALUES (?, ?, ?, ?, ?)")) {
            insert.setString(1, profile.id().toString());
            insert.setString(2, profile.name());
            insert.setLong(3, profile.firstSeen());
            insert.setLong(4, profile.lastSeen());
            insert.setInt(5, profile.joins());
            insert.executeUpdate();
        }
    }
}
