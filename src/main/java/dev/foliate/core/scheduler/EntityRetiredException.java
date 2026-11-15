package dev.foliate.core.scheduler;

import java.io.Serial;

/**
 * Completes a future whose work was bound to an entity that no longer exists, typically a player
 * who logged out before the task could run. Usually safe to ignore.
 */
public final class EntityRetiredException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public EntityRetiredException() {
        // No stack trace: this is an expected outcome, not a programming error.
        super("The entity was removed before the task could run", null, false, false);
    }
}
