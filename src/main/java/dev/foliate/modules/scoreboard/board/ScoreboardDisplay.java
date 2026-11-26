package dev.foliate.modules.scoreboard.board;

import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * One player's scoreboard. Hides the scoreboard library from the module so the module's logic can be
 * tested without a real server.
 */
public interface ScoreboardDisplay {

    /** Redraws the board. Does nothing once it is deleted, even when that happens on another thread. */
    void update(Component title, List<Component> lines);

    /** Removes the scoreboard from the player's screen. */
    void delete();
}
