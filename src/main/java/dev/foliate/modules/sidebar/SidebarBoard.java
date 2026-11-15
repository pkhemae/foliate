package dev.foliate.modules.sidebar;

import java.util.List;
import net.kyori.adventure.text.Component;

/**
 * One player's sidebar. Hides the scoreboard library from the module so the module's logic can be
 * tested without a real server.
 */
interface SidebarBoard {

    void update(Component title, List<Component> lines);

    /** Removes the sidebar from the player's screen. */
    void delete();
}
