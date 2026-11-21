package dev.foliate.core.module;

/**
 * A self-contained part of the plugin with its own lifecycle.
 *
 * <p>{@link #enable()} runs on the server startup thread and {@link #disable()} on whichever thread
 * disables the plugin: the shutdown thread when the server stops. {@link #reload()} runs on
 * whichever thread asked for the reload, which must not be a tick thread: it may do blocking I/O,
 * but has to go through the schedulers to touch the world.
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
     *     error. Throw it before setting anything up: {@link #disable()} is not called after it
     * @throws Exception if the module cannot start
     */
    void enable() throws Exception;

    /**
     * Re-reads whatever the module loaded at startup. A failed reload must leave the previous
     * state in place.
     *
     * <p>When the plugin is disabled during a reload, the reloading thread is interrupted, and
     * shutdown waits for this method to return. A reload that waits, for a region or anything else,
     * must therefore wait interruptibly and, once interrupted, give up at once: keep the previous
     * state, throw, and leave the interrupt status set.
     *
     * @throws Exception if the new state is invalid, or the reload was interrupted
     */
    default void reload() throws Exception {}

    /**
     * Stops the module. Must cope with a module that only got halfway through {@link #enable()}.
     *
     * <p>Nothing can be scheduled any more, not even async tasks: do what is left synchronously,
     * on the calling thread (blocking to save data is fine), and do not touch the world.
     *
     * <p>Whether the regions still tick depends on why the plugin is disabled. When Folia stops,
     * they have stopped. When only the plugin is disabled, by Paper's {@code /reload}, a plugin
     * manager, or after a failed start, they still tick: new events and tasks no longer reach the
     * plugin, but one already under way on a region thread may still be running, and this method
     * may itself be called from a tick thread. State shared with such code must stay thread-safe
     * until the end, and nothing it could still add may be left behind.
     */
    default void disable() {}
}
