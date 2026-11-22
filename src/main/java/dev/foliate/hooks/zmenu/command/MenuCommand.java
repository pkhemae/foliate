package dev.foliate.hooks.zmenu.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.core.command.Subcommand;
import dev.foliate.core.message.MessageService;
import dev.foliate.hooks.zmenu.ZMenuHook;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.function.BiConsumer;
import org.bukkit.entity.Player;

/**
 * {@code menu}: opens the example menu for the player the command runs as. Offered by the zMenu
 * hook, so it only exists when zMenu is installed and the hook runs.
 *
 * <p>Whoever has the permission sees it, the console included: the console opens it for a player
 * with {@code /execute as <player> run foliate menu}. Whether the command runs as a player is only
 * known when it runs, so that is checked then, not by {@link Subcommand#isAvailableTo}.
 */
public final class MenuCommand implements Subcommand {

    /** Declared in the plugin.yml with the other nodes. */
    static final String PERMISSION = "foliate.command.menu";

    private static final String MENU = "example";

    private final BiConsumer<Player, String> open;
    private final MessageService messages;

    /** @param open opens a menu for a player, from any thread: {@link ZMenuHook#open} */
    public MenuCommand(BiConsumer<Player, String> open, MessageService messages) {
        this.open = open;
        this.messages = messages;
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
    public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
        node.executes(context -> {
            // The executor is the player the command runs as, which differs from the sender under
            // /execute as, and is only set by then. Opening hops to that player's region by itself.
            CommandSourceStack source = context.getSource();
            if (source.getExecutor() instanceof Player player) {
                open.accept(player, MENU);
            } else {
                messages.send(source.getSender(), "command.menu.player-required");
            }
            return Command.SINGLE_SUCCESS;
        });
    }
}
