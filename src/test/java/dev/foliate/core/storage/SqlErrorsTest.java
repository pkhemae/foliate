package dev.foliate.core.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLTransientConnectionException;
import org.junit.jupiter.api.Test;

class SqlErrorsTest {

    @Test
    void anUnreachableOrBusyDatabaseMayPass() {
        assertTrue(SqlErrors.mayPass(new SQLException("Communications link failure", "08S01")));
        assertTrue(SqlErrors.mayPass(new SQLException("Deadlock found", "40001")));
        assertTrue(SqlErrors.mayPass(new SQLTransientConnectionException("Connection is not available")));
        assertTrue(SqlErrors.mayPass(sqlite("SQLITE_BUSY")));
    }

    /** These fail the same way every time: retried forever, they would hold a player's queue. */
    @Test
    void aStatementAtFaultDoesNotPass() {
        assertFalse(SqlErrors.mayPass(new SQLIntegrityConstraintViolationException("Duplicate entry", "23000")));
        assertFalse(SqlErrors.mayPass(sqlite("SQLITE_CONSTRAINT")));
        assertFalse(SqlErrors.mayPass(new SQLException("no such table: profiles")));
    }

    /** An error as the SQLite driver makes it. The driver is the server's: only on the runtime path. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static SQLException sqlite(String code) {
        try {
            Class<?> codes = Class.forName("org.sqlite.SQLiteErrorCode");
            Object value = Enum.valueOf((Class) codes, code);
            return Class.forName("org.sqlite.SQLiteException")
                    .asSubclass(SQLException.class)
                    .getConstructor(String.class, codes)
                    .newInstance(code, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
