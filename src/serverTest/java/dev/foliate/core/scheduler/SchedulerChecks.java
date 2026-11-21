package dev.foliate.core.scheduler;

/** What the server test asks of the schedulers beyond their interface. */
public final class SchedulerChecks {

    private SchedulerChecks() {}

    /**
     * Whether a timer is still kept by the schedulers until shutdown.
     *
     * @throws IllegalArgumentException when the schedulers are not the server's: the answer would
     *     mean nothing
     */
    public static boolean isTracked(Schedulers schedulers, TaskHandle timer) {
        if (!(schedulers instanceof FoliaSchedulers folia)) {
            throw new IllegalArgumentException("Not the server's schedulers: " + schedulers.getClass().getName());
        }
        return folia.isTracked(timer);
    }
}
