package dev.foliate.core.scheduler;

/** A scheduled task that can be cancelled from any thread. */
public interface TaskHandle {

    /** Cancels the task. A run already in progress finishes; cancelling twice is harmless. */
    void cancel();

    boolean isCancelled();
}
