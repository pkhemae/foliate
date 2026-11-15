package dev.foliate.core.message;

import java.io.Serial;

/** The language files could not be read or do not match the configuration. */
public final class MessageException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public MessageException(String message) {
        super(message);
    }

    public MessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
