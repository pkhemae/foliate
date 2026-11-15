package dev.foliate.core.module;

/**
 * A self-contained part of the plugin with its own lifecycle.
 *
 * <p>{@link #enable()} runs on the server startup thread and {@link #disable()} on the shutdown
 * thread. {@link #reload()} runs on whichever thread asked for the reload, which must not be a
 * tick thread: it may do blocking I/O, but has to go through the schedulers to touch the world.
 */
public interface Module {

    /** Short, stable identifier used in logs and in the info command. */
    String name();

    /**
     * Whether the plugin cannot run without this module. When a required module fails to enable,
     * the modules enabled before it are disabled again and the plugin shuts itself down.
     */
    default boolean required() {
        return false;
    }

    /**
     * Starts the module. Throwing leaves the module disabled without affecting the others;
     * {@link #disable()} is then called to undo whatever had already been set up.
     *
     * @throws ModuleSkippedException if the module has nothing to do in this setup, for example
     *     because it is turned off in the configuration; this is logged as information, not as an
     *     error
     * @throws Exception if the module cannot start
     */
    void enable() throws Exception;

    /**
     * Re-reads whatever the module loaded at startup. A failed reload must leave the previous
     * state in place.
     *
     * @throws Exception if the new state is invalid
     */
    default void reload() throws Exception {}

    /**
     * Stops the module. Must cope with a module that only got halfway through {@link #enable()}.
     *
     * <p>During shutdown nothing can be scheduled any more, not even async tasks, and region
     * tickers are stopped: do what is left synchronously (blocking is fine here), and do not touch
     * the world.
     */
    default void disable() {}
}
