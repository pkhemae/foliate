package dev.foliate.core.storage;

import java.sql.Connection;
import java.sql.SQLException;

/** Work done with a database connection. The connection is closed by the caller. */
@FunctionalInterface
public interface SqlFunction<T> {

    T apply(Connection connection) throws SQLException;
}
