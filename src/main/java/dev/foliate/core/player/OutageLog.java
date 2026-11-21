package dev.foliate.core.player;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;

/**
 * Logs an outage when the first player's saves start failing and when the last one's succeed, not
 * once per save, whatever the other players' saves do meanwhile. Thread-safe.
 */
final class OutageLog {

    private final String subject;
    private final Logger logger;

    /** The players whose save is being retried. Guarded by itself. */
    private final Set<UUID> retrying = new HashSet<>();

    /** @param subject what the values are, in singular, for the logs ("profile") */
    OutageLog(String subject, Logger logger) {
        this.subject = subject;
        this.logger = logger;
    }

    /** A player's save is retried: logs the start of an outage if they are the first. */
    void retryStarted(UUID id, Throwable cause) {
        synchronized (retrying) {
            if (retrying.add(id) && retrying.size() == 1) {
                logger.warn("Could not save the {} of {}, retrying: {}", subject, id, Retries.describe(cause));
            }
        }
    }

    /**
     * A player's save is no longer retried: logs the end of an outage if they were the last and
     * their save landed.
     */
    void retryEnded(UUID id, boolean saved) {
        synchronized (retrying) {
            if (retrying.remove(id) && retrying.isEmpty() && saved) {
                logger.info("Player {}s are saved again", subject);
            }
        }
    }
}
