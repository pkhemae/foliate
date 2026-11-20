package dev.foliate.core.player;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

/**
 * The SQL of one kind of player data. Plain JDBC, written to run unchanged on SQLite and MySQL.
 * Both methods run on a storage thread, never on a tick thread.
 */
public interface PlayerRepository<T extends PlayerData> {

    /** Reads a player's row, or empty when there is none. */
    Optional<T> find(Connection connection, UUID id) throws SQLException;

    /** Inserts the row of a player, or updates it when it exists. */
    void save(Connection connection, T value) throws SQLException;
}
