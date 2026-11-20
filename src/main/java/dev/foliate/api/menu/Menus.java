package dev.foliate.api.menu;

import org.bukkit.entity.Player;

/**
 * Opens menus for players. The rest of the plugin depends on this interface only, so features keep
 * working, without menus, when no menu plugin is installed.
 */
public interface Menus {

    /**
     * Opens a menu. Safe to call from any thread: the menu is opened from the player's own region.
     * The player is told when the menu does not exist.
     *
     * @param menu the file name of the menu under {@code inventories/}, without the extension
     */
    void open(Player player, String menu);
}
