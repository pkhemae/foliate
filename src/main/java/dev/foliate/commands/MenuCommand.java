package dev.foliate.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.core.menu.Menus;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.entity.Player;

/**
 * {@code menu}: opens the example menu. Only registered when a menu plugin is installed, and only
 * available when the command runs as a player.
 */
public final class MenuCommand implements Subcommand {

    private static final String MENU = "example";

    private final Menus menus;

    public MenuCommand(Menus menus) {
        this.menus = menus;
    }

    @Override
    public String name() {
        return "menu";
    }

    @Override
    public String permission() {
        return Permissions.COMMAND_MENU;
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
