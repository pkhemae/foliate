package dev.foliate.core.module;

import java.io.Serial;

/**
 * Thrown from {@link Module#enable()} by a module that chooses not to run in the current setup:
 * turned off in the configuration, or missing something optional it depends on. The module stays
 * disabled and the reason is logged without alarm.
 */
public final class ModuleSkippedException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public ModuleSkippedException(String reason) {
        super(reason);
    }
}
