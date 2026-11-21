package dev.foliate.core.storage;

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientException;
import java.util.Set;

/** Tells apart the SQL errors that a caller can do something about. */
public final class SqlErrors {

    /** MySQL's and MariaDB's ER_DUP_ENTRY. */
    private static final int MYSQL_DUPLICATE_ENTRY = 1062;

    /** SQLITE_BUSY, SQLITE_LOCKED, SQLITE_IOERR, SQLITE_FULL and SQLITE_CANTOPEN. */
    private static final Set<Integer> SQLITE_PASSING = Set.of(5, 6, 10, 13, 14);

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
     * Whether the same work is known to succeed later: the database was unreachable, busy, locked,
     * out of space or too slow, or the transaction was rolled back for a deadlock. False for what
     * says nothing about it, which includes a broken constraint or a value that does not fit on
     * both drivers (SQLite reports every error with the same type, MySQL a value too long as a
     * warning): a caller may still try those again a few times, but not forever.
     */
    public static boolean mayPass(SQLException e) {
        if (e instanceof SQLTransientException || e instanceof SQLRecoverableException
                || e instanceof SQLNonTransientConnectionException) {
            return true;
        }
        String state = e.getSQLState();
        if (state != null && (state.startsWith("08") || state.startsWith("40"))) {
            // 08: the connection failed. 40: the transaction was rolled back, as for a deadlock.
            return true;
        }
        // SQLite gives its result code, without a state: busy, locked, a disk error, a full disk,
        // a file it cannot open. The driver's class is only checked by name: it belongs to the
        // server, not to the plugin.
        return e.getClass().getName().startsWith("org.sqlite.") && SQLITE_PASSING.contains(e.getErrorCode() & 0xFF);
    }
}
