package dev.foliate.core.config;

import java.io.Serial;

/** The configuration file could not be read or holds an invalid value. */
public final class ConfigException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public ConfigException(String message) {
        super(message);
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
    }
}
