package dev.foliate.sidebar;

import fr.mrmicky.fastboard.adventure.FastBoard;
import java.util.List;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;

/** Sidebar backed by FastBoard, which sends scoreboard packets straight to the player. */
final class FastBoardSidebar implements SidebarBoard {

    private final FastBoard board;

    FastBoardSidebar(Player player) {
        this.board = new FastBoard(player);
    }

    /**
     * FastBoard looks up server internals when its class is first used, and fails there on a
     * server version it does not know. Doing it up front turns that into a clean startup error
     * instead of an exception on every join.
     */
    static void ensureSupported() throws SidebarUnavailableException {
        try {
            Class.forName(FastBoard.class.getName(), true, FastBoardSidebar.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            throw new SidebarUnavailableException("FastBoard does not support this server version", e);
        }
    }

    @Override
    public void update(Component title, List<Component> lines) {
        board.updateTitle(title);
        board.updateLines(lines);
    }

    @Override
    public void delete() {
        if (!board.isDeleted()) {
            board.delete();
        }
    }
}
