package dev.foliate.core.message;

import java.io.Serial;

/** messages.yml could not be read, or is not valid. */
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
