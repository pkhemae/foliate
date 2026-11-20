package dev.foliate.modules.scoreboard;

import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * One player's scoreboard. Hides the scoreboard library from the module so the module's logic can be
 * tested without a real server.
 */
interface ScoreboardDisplay {

    void update(Component title, List<Component> lines);

    /** Removes the scoreboard from the player's screen. */
    void delete();
}
