package dev.foliate.core.scheduler;

import java.io.Serial;
import java.util.concurrent.RejectedExecutionException;

/**
 * Work was bound to an entity that no longer exists, typically a player who logged out before the
 * task could run. Thrown by {@link TaskContext#execute} and used to fail the futures of
 * {@link TaskContext#run} and {@link TaskContext#supply}. Usually safe to ignore.
 */
public final class EntityRetiredException extends RejectedExecutionException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EntityRetiredException() {
        super("The entity was removed before the task could run");
    }

    /** No stack trace: this is an expected outcome, not a programming error. */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
