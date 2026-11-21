package dev.foliate.core.module;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;

/**
 * Runs the lifecycle of every {@link Module}: enables them in registration order, disables them in
 * reverse, and keeps one module's failure from taking the others down.
 *
 * <p>Lifecycle methods are serialised by a lock, so a reload triggered by two senders at once runs
 * twice in a row rather than concurrently. They run on the calling thread: {@link #reloadAll()}
 * must be called off the tick threads, since modules do blocking I/O there.
 */
public final class ModuleManager {

    private enum State {
        NEW,
        ENABLED,
        DISABLED
    }

    private final Logger logger;
    private final List<Module> registered = new ArrayList<>();
    private final List<Module> enabled = new ArrayList<>();
    private State state = State.NEW;
    private volatile List<String> enabledNames = List.of();

    public ModuleManager(Logger logger) {
        this.logger = logger;
    }

    /**
     * Registers a module. Order matters: a module goes after the ones it depends on.
     *
     * @throws IllegalArgumentException when a module of the same name is registered: the logs,
     *     the reload report and {@link #enabledNames()} tell modules apart by their name
     */
    public synchronized void register(Module module) {
        if (state != State.NEW) {
            throw new IllegalStateException("Modules must be registered before enableAll()");
        }
        if (registered.stream().anyMatch(other -> other.name().equals(module.name()))) {
            throw new IllegalArgumentException("A module named '" + module.name() + "' is already registered");
        }
        registered.add(module);
    }

    /**
     * Enables every registered module. Can only be called once.
     *
     * @return false when a {@linkplain Module#required() required} module failed: everything has
     *     been disabled again and the plugin must not keep running
     */
    public synchronized boolean enableAll() {
        if (state != State.NEW) {
            throw new IllegalStateException("enableAll() has already been called");
        }
        state = State.ENABLED;
        for (Module module : registered) {
            try {
                module.enable();
                enabled.add(module);
            } catch (ModuleSkippedException e) {
                if (module.required()) {
                    // The plugin cannot run without it, whatever the reason it gives.
                    logger.error("Required module '{}' is not enabled, the plugin cannot start: {}",
                            module.name(), e.getMessage());
                    disableAll();
                    return false;
                }
                logger.info("Module '{}' is not enabled: {}", module.name(), e.getMessage());
            } catch (Exception | LinkageError e) {
                disable(module);
                if (module.required()) {
                    log("Required module '{}' failed to enable, the plugin cannot start", module, e);
                    disableAll();
                    return false;
                }
                log("Module '{}' failed to enable and stays disabled", module, e);
            }
        }
        publishEnabledNames();
        return true;
    }

    /**
     * Reloads every enabled module. Must not be called from a tick thread.
     *
     * @return the failures by module name, empty when everything reloaded
     * @throws IllegalStateException when the modules are not enabled: there would be nothing to
     *     reload, and an empty result would read as a success
     */
    public synchronized Map<String, Throwable> reloadAll() {
        if (state != State.ENABLED) {
            throw new IllegalStateException("The modules are not enabled");
        }
        Map<String, Throwable> failures = new LinkedHashMap<>();
        for (Module module : enabled) {
            try {
                module.reload();
            } catch (Exception | LinkageError e) {
                log("Module '{}' failed to reload, previous state kept", module, e);
                failures.put(module.name(), e);
            }
        }
        return failures;
    }

    public synchronized void disableAll() {
        for (Module module : enabled.reversed()) {
            disable(module);
        }
        enabled.clear();
        state = State.DISABLED;
        publishEnabledNames();
    }

    /** Names of the modules currently enabled, safe to read from any thread. */
    public List<String> enabledNames() {
        return enabledNames;
    }

    /** The enabled modules that are of a given type, for instance a capability they implement. */
    public synchronized <T> List<T> enabledOf(Class<T> type) {
        return enabled.stream().filter(type::isInstance).map(type::cast).toList();
    }

    private void disable(Module module) {
        try {
            module.disable();
        } catch (Exception | LinkageError e) {
            log("Module '{}' failed to disable cleanly", module, e);
        }
    }

    /**
     * A checked exception is how a module reports a problem it anticipated, such as an invalid
     * file: its message is enough. Anything else is a bug and gets a stack trace.
     */
    private void log(String message, Module module, Throwable failure) {
        if (failure instanceof RuntimeException || failure instanceof Error) {
            logger.error(message, module.name(), failure);
        } else {
            logger.error(message + ": {}", module.name(), failure.getMessage());
        }
    }

    private void publishEnabledNames() {
        enabledNames = enabled.stream().map(Module::name).toList();
    }
}
