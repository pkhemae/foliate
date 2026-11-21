package dev.foliate.hooks.placeholderapi;

import java.io.Serial;

/** PlaceholderAPI did not take the plugin's placeholders. The message is written for an administrator. */
public final class PlaceholderApiException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    PlaceholderApiException(String message) {
        super(message);
    }
}
