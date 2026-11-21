package dev.foliate.core.storage;

import java.sql.Connection;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/** Work done with a database connection. The connection is closed by the caller. */
@FunctionalInterface
public interface SqlFunction<T extends @Nullable Object> {

    T apply(Connection connection) throws SQLException;
}
