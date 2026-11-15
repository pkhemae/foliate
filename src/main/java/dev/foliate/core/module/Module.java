package dev.foliate.core.module;

/**
 * A self-contained part of the plugin with its own lifecycle.
 *
 * <p>{@link #enable()} and {@link #disable()} run on the server startup and shutdown threads.
 * {@link #reload()} runs off-region, on an async thread: it may do blocking I/O, but must go
 * through the schedulers to touch the world.
 */
public interface Module {

    /** Short, stable identifier used in logs and in the info command. */
    String name();

    /**
     * Starts the module. Throwing leaves the module disabled without affecting the others.
     *
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
     * Stops the module. Region tickers are already stopped when the server shuts down, so this
     * must not schedule region or entity work: close resources and flush data only.
     */
    default void disable() {}
}
