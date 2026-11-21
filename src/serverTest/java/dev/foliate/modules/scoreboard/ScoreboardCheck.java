package dev.foliate.modules.scoreboard;

import dev.foliate.servertest.PlayerCheck;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** A connected player has a board when boards are on, and none is kept once they left. */
public final class ScoreboardCheck implements PlayerCheck {

    private final ScoreboardModule scoreboard;

    public ScoreboardCheck(ScoreboardModule scoreboard) {
        this.scoreboard = scoreboard;
    }

    @Override
    public @Nullable String problemWith(Player player, int visit) {
        if (!scoreboard.showsBoards() || scoreboard.hasBoard(player.getUniqueId())) {
            return null;
        }
        return "no scoreboard was created for " + player.getName();
    }

    @Override
    public @Nullable String problemAfterQuit(UUID id, String name) {
        return scoreboard.hasBoard(id) ? "the scoreboard of " + name + " was kept after the player left" : null;
    }
}
