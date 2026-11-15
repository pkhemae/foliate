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
 * twice in a row rather than concurrently.
 */
public final class ModuleManager {

    private final Logger logger;
    private final List<Module> registered = new ArrayList<>();
    private final List<Module> enabled = new ArrayList<>();
    private volatile List<String> enabledNames = List.of();

    public ModuleManager(Logger logger) {
        this.logger = logger;
    }

    /** Registers a module. Order matters: a module goes after the ones it depends on. */
    public synchronized void register(Module module) {
        if (!enabled.isEmpty()) {
            throw new IllegalStateException("Modules must be registered before enableAll()");
        }
        registered.add(module);
    }

    public synchronized void enableAll() {
        for (Module module : registered) {
            try {
                module.enable();
                enabled.add(module);
            } catch (Exception | LinkageError e) {
                logger.error("Module '{}' failed to enable and stays disabled", module.name(), e);
            }
        }
        publishEnabledNames();
    }

    /**
     * Reloads every enabled module.
     *
     * @return the failures by module name, empty when everything reloaded
     */
    public synchronized Map<String, Exception> reloadAll() {
        Map<String, Exception> failures = new LinkedHashMap<>();
        for (Module module : enabled) {
            try {
                module.reload();
            } catch (Exception e) {
                logger.error("Module '{}' failed to reload, previous state kept", module.name(), e);
                failures.put(module.name(), e);
            }
        }
        return failures;
    }

    public synchronized void disableAll() {
        for (Module module : enabled.reversed()) {
            try {
                module.disable();
            } catch (Exception | LinkageError e) {
                logger.error("Module '{}' failed to disable cleanly", module.name(), e);
            }
        }
        enabled.clear();
        publishEnabledNames();
    }

    /** Names of the modules currently enabled, safe to read from any thread. */
    public List<String> enabledNames() {
        return enabledNames;
    }

    private void publishEnabledNames() {
        enabledNames = enabled.stream().map(Module::name).toList();
    }
}
