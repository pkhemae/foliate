package dev.foliate.core.config;

import java.io.Serial;

/**
 * Thrown by a ConfigLib serializer of the plugin to refuse a value. {@link ConfigService} shows its
 * message to the administrator as it is, so the message names the setting and what it takes, and
 * never quotes the value: it may be a password.
 */
public final class InvalidSettingException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    public InvalidSettingException(String message) {
        super(message);
    }
}
