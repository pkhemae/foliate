package dev.foliate.modules.scoreboard.board;

import java.io.Serial;

/** Scoreboards cannot work on this server, for example because its version is too recent. */
public final class ScoreboardUnavailableException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    public ScoreboardUnavailableException(String message) {
        super(message);
    }

    ScoreboardUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
