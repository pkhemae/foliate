package dev.foliate.core.command;

import com.mojang.brigadier.suggestion.SuggestionProvider;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.Locale;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A command argument that names a player online, as the sender sees the server: a player the
 * sender cannot see, such as one who vanished, is neither suggested nor found. Give the argument
 * {@link #suggestions}, then look the name up with {@link #find} when the command runs:
 *
 * <pre>{@code
 * Commands.argument("player", StringArgumentType.word())
 *         .suggests(OnlinePlayers.suggestions(server))
 *         .executes(context -> {
 *             CommandSender sender = context.getSource().getSender();
 *             Player target = OnlinePlayers.find(server, sender, StringArgumentType.getString(context, "player"));
 *             ...
 *         })
 * }</pre>
 *
 * <p>Folia: only the list of players, their names and who may see whom are read, which is safe
 * from the sender's region. Anything of the player found goes through that player's scheduler.
 * {@link PlayerTarget} builds on this for subcommands about yourself or another player.
 */
public final class OnlinePlayers {

    private OnlinePlayers() {}

    /** Suggests the names of the players online that the sender can see, which start with what is typed. */
    public static SuggestionProvider<CommandSourceStack> suggestions(Server server) {
        return (context, suggestions) -> {
            CommandSender sender = context.getSource().getSender();
            String typed = suggestions.getRemainingLowerCase();
            for (Player online : server.getOnlinePlayers()) {
                if (canSee(sender, online) && online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                    suggestions.suggest(online.getName());
                }
            }
            return suggestions.buildFuture();
        };
    }

    /**
     * The player online with this name, whatever its case, or null when there is none or the sender
     * cannot see them: answer both alike, so that a vanished player is not given away.
     */
    public static @Nullable Player find(Server server, CommandSender sender, String name) {
        Player player = server.getPlayerExact(name);
        return player != null && canSee(sender, player) ? player : null;
    }

    /** Whether the sender can see the player: always for the console and command blocks. */
    public static boolean canSee(CommandSender sender, Player player) {
        return !(sender instanceof Player viewer) || viewer.canSee(player);
    }
}
