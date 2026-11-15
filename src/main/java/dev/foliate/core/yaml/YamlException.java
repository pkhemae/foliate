package dev.foliate.core.yaml;

import java.io.Serial;

/** A document is not valid YAML, or is not a mapping at its root. */
public final class YamlException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public YamlException(String message, Throwable cause) {
        super(message, cause);
    }
}
