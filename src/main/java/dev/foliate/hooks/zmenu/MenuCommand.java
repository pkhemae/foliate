package dev.foliate.hooks.zmenu;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.contracts.menu.Menus;
import dev.foliate.core.command.Subcommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

/**
 * {@code menu}: opens the example menu. Offered by the zMenu hook, so it only exists when zMenu is
 * installed and the hook runs, and only available when the command runs as a player.
 */
final class MenuCommand implements Subcommand {

    /** Declared in the plugin.yml with the other nodes. */
    static final String PERMISSION = "foliate.command.menu";

    private static final String MENU = "example";

    private final Menus menus;

    MenuCommand(Menus menus) {
        this.menus = menus;
    }

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public boolean isAvailableTo(CommandSourceStack source) {
        return source.getExecutor() instanceof Player;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
        node.executes(context -> {
            // The executor is the player the command runs as, which differs from the sender under
            // /execute as. Menus#open hops to that player's region by itself.
            if (context.getSource().getExecutor() instanceof Player player) {
                menus.open(player, MENU);
            }
            return Command.SINGLE_SUCCESS;
        });
    }
}
