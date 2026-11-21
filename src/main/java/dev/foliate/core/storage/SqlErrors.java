package dev.foliate.core.storage;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;

/** Tells apart the SQL errors that a caller can do something about. */
public final class SqlErrors {

    /** MySQL's and MariaDB's ER_DUP_ENTRY. */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    private SqlErrors() {}

    /**
     * Whether an insert failed because a row with the same key already exists: another server
     * sharing a MySQL database inserted it first. On SQLite only one server writes, and the saves
     * of one player are queued one after the other, so this cannot happen there.
     */
    public static boolean isDuplicateKey(SQLException e) {
        return e.getErrorCode() == MYSQL_DUPLICATE_ENTRY;
    }

    /**
     * Whether the same work may succeed if it is tried again: the database was unreachable, busy,
     * locked or too slow. Not when the statement itself is at fault (bad SQL, a broken constraint,
     * a value that does not fit), which only fails again. Errors that do not say which they are,
     * as most of SQLite's, are taken as passing.
     */
    public static boolean mayPass(SQLException e) {
        if (e instanceof SQLNonTransientConnectionException) {
            return true;
        }
        return !(e instanceof SQLNonTransientException);
    }
}
