package dev.foliate.hooks.zmenu;

import java.io.Serial;

/** Menus could not be loaded. The message is written for an administrator. */
public final class MenuException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public MenuException(String message, Throwable cause) {
        super(message, cause);
    }
}
