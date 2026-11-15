package dev.foliate.sidebar;

import java.io.Serial;

/** Sidebars cannot work on this server, for example because its version is too recent. */
public final class SidebarUnavailableException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    SidebarUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
