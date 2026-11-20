package dev.foliate.core.storage;

import java.sql.SQLException;

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
}
