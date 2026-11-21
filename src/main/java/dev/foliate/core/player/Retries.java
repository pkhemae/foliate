package dev.foliate.core.player;

import dev.foliate.core.storage.SqlErrors;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongFunction;

/**
 * When a store tries a failed database operation again: a save, or the load of a player who joined
 * without a pre-login. An error known to pass (the database is unreachable, busy or timed out) is
 * tried again until it succeeds, ever less often; any other database error is tried
 * {@value #UNCERTAIN_RETRIES} more times; anything else is not tried again.
 */
final class Retries {

    /** The wait before the first retry; it doubles each time. */
    static final long FIRST_MILLIS = 250;

    /** The longest wait between two tries. */
    static final long MAX_MILLIS = 10_000;

    /** How many times an error that may or may not pass is tried again before giving up. */
    static final int UNCERTAIN_RETRIES = 3;

    /**
     * Runs a retry after a delay. Not the plugin's {@code Schedulers}: the server refuses its
     * async tasks while the plugin is disabled, and the saves of the shutdown must still be
     * retried then. Replaced in the tests, which do not wait for real delays.
     */
    private volatile LongFunction<Executor> delays =
            millis -> CompletableFuture.delayedExecutor(millis, TimeUnit.MILLISECONDS);

    /** Makes the retries run at once, or when a test says. */
    void delays(LongFunction<Executor> delays) {
        this.delays = delays;
    }

    /** Runs {@code retry} once the wait that follows {@code failures} failures in a row is over. */
    void after(int failures, Runnable retry) {
        delays.apply(delay(failures)).execute(retry);
    }

    /**
     * Whether trying again may succeed: always when the database was unreachable, busy or late,
     * a few times for an error that does not say, never for anything but a database error.
     */
    static boolean worthRetrying(Throwable error, int failures) {
        return error instanceof SQLException sql && (SqlErrors.mayPass(sql) || failures < UNCERTAIN_RETRIES);
    }

    /** How long to wait after a number of failures in a row: doubling, up to a limit. */
    static long delay(int failures) {
        return Math.min(MAX_MILLIS, FIRST_MILLIS << Math.min(failures, 16));
    }

    /** The error a future failed with, rather than the wrapper the future adds around it. */
    static Throwable unwrap(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    /** An error in one line, for a warning that would otherwise repeat a whole stack trace. */
    static String describe(Throwable error) {
        String message = error.getMessage();
        String first = message == null ? "" : message.lines().findFirst().orElse("");
        return first.isEmpty() ? error.getClass().getSimpleName() : first;
    }
}
