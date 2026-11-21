package dev.foliate.core.player;

import java.util.Optional;

/**
 * A queued save, and the value it writes, which can change until it starts: changes made while it
 * waits for a storage thread join it instead of queuing saves of their own. Thread-safe.
 */
final class PendingSave<T> {

    private T value;
    private boolean started;
    private boolean doubtful;

    PendingSave(T value) {
        this.value = value;
    }

    /**
     * Makes the save write a newer value, unless it has started.
     *
     * @return false when the save has started: the value must then be saved by another one
     */
    synchronized boolean replace(T newer) {
        if (started) {
            return false;
        }
        value = newer;
        return true;
    }

    synchronized T start() {
        started = true;
        return value;
    }

    /**
     * The value it will write, while it has not started: then it is the latest one. Not for a
     * save that failed for a reason not known to pass, which the database may refuse.
     */
    synchronized Optional<T> peek() {
        return started || doubtful ? Optional.empty() : Optional.of(value);
    }

    /** After a failure: the value is written again by a retry, with the changes made until then. */
    synchronized void reopen(boolean doubtful) {
        started = false;
        this.doubtful = doubtful;
    }
}
