package dev.foliate.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.foliate.core.message.MessageService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * The player a subcommand of the shape {@code /foliate <thing> [player]} is about: the one it runs
 * as, or the one named. Anyone with the subcommand's permission gets their own; another player
 * needs a second permission, the {@code others} one. {@link #addTo} adds both forms to the node,
 * with every check:
 *
 * <ul>
 *   <li>without a name, the target is the executor: the player who typed it, or the one given to
 *       {@code /execute as}. Running it as oneself is allowed; as another player, it needs
 *       {@code others}. That check is made when the command runs, from
 *       {@link CommandSourceStack#getExecutor()}: Brigadier evaluates requirements while it parses,
 *       with the source as typed, and {@code /execute as} only swaps the executor afterwards (see
 *       {@link Subcommand#isAvailableTo}). A source with no player as executor, such as the console
 *       or a command block, is asked to name a player;
 *   <li>the name argument requires {@code others} of the sender, whom {@code /execute as} does not
 *       change. A player who is offline, or whom the sender cannot see (one who vanished), is
 *       reported as not online;
 *   <li>the players online are suggested while typing, only to a sender with {@code others}, and
 *       only those the sender can see (see {@link OnlinePlayers}).
 * </ul>
 *
 * <p>The refusals are messages of the subcommand's own, in one section of {@code messages.yml}:
 * {@code <section>.name-required}, {@code <section>.unknown} (given {@code <player>}, the name as
 * typed) and {@code <section>.others-denied}. They name the command and what it shows, so a player
 * reads which one refused.
 *
 * <p>Folia: the action runs on the sender's region, not on the target's: the region of the player
 * who typed the command, the global region for the console, the block's region for a command
 * block. It may read the target's name and unique id, which are safe from any thread, and data the
 * plugin keeps by unique id, such as a cache; anything of the target's body, location or world
 * goes through the target's scheduler. The checks here only read the sender, the executor
 * reference, the name and the unique id.
 *
 * <p>The target was online when it was checked, but a player's quit is handled on their own
 * region, in parallel with the action: an action that looks the target up, in a cache for
 * example, must cope with finding nothing, as the profile command does with its
 * {@code not-loaded} message.
 *
 * <p>A subcommand creates one in its constructor, from its {@code PERMISSION_OTHERS} constant and
 * the section of its messages, and calls {@link #addTo} from its {@link Subcommand#build}, with
 * what to do for the target: {@code target.addTo(node, this::show)}.
 */
public final class PlayerTarget {

    /** The name of the argument unless told otherwise, as the client shows it: {@code /foliate <thing> [<player>]}. */
    private static final String DEFAULT_ARGUMENT = "player";

    private static final List<String> MESSAGES = List.of("name-required", "unknown", "others-denied");

    /** What the subcommand does once it knows its target. */
    @FunctionalInterface
    public interface Action {

        /**
         * Does what the subcommand is for, once every check has passed.
         *
         * @param sender who typed the command, to answer
         * @param target the player it is about, who may be the sender: online when checked, and
         *     visible to the sender when named. They may have quit since, from their own region,
         *     so data looked up by their unique id may be missing
         */
        void run(CommandSender sender, Player target);
    }

    private final Server server;
    private final MessageService messages;
    private final String othersPermission;
    private final String section;
    private final String argument;
    private final SuggestionProvider<CommandSourceStack> onlinePlayers;

    /**
     * @param othersPermission the node that allows targeting another player: the subcommand's
     *     {@code PERMISSION_OTHERS} constant, so that it is declared next to it and in the plugin.yml
     * @param section the section of {@code messages.yml} that holds the refusals (see
     *     {@link #messageKeys}), written inline as a string literal such as
     *     {@code "command.profile"}: a test then checks that its keys exist, and fails on anything
     *     else, a section swapped with the permission included
     */
    public PlayerTarget(Server server, MessageService messages, String othersPermission, String section) {
        this(server, messages, othersPermission, section, DEFAULT_ARGUMENT);
    }

    /**
     * @param argument the name of the player argument, which the client shows while typing and a
     *     usage shows: {@code joueur} for a plugin in French, {@code /fly time [<joueur>]}
     * @see #PlayerTarget(Server, MessageService, String, String)
     */
    public PlayerTarget(Server server, MessageService messages, String othersPermission, String section, String argument) {
        this.server = server;
        this.messages = messages;
        this.othersPermission = othersPermission;
        this.section = section;
        this.argument = argument;
        this.onlinePlayers = OnlinePlayers.suggestions(server);
    }

    /** The keys of the refusals, which {@code messages.yml} must hold under that section. */
    public static List<String> messageKeys(String section) {
        return MESSAGES.stream().map(key -> section + "." + key).toList();
    }

    /**
     * Makes the node run the action, on its executor when no name is given, and on the named
     * player otherwise. It sets the node's {@code executes} and adds the argument: the subcommand
     * adds nothing else that executes.
     */
    public void addTo(LiteralArgumentBuilder<CommandSourceStack> node, Action action) {
        node.executes(context -> {
            CommandSourceStack source = context.getSource();
            CommandSender sender = source.getSender();
            if (!(source.getExecutor() instanceof Player executor)) {
                messages.send(sender, section + ".name-required");
            } else if (isSelf(sender, executor) || sender.hasPermission(othersPermission)) {
                action.run(sender, executor);
            } else {
                messages.send(sender, section + ".others-denied");
            }
            return Command.SINGLE_SUCCESS;
        });
        node.then(Commands.argument(argument, StringArgumentType.word())
                // The sender is the one who typed the command, whatever /execute as did: a
                // requirement on it holds when the command runs.
                .requires(source -> source.getSender().hasPermission(othersPermission))
                .suggests((context, suggestions) -> {
                    CommandSender sender = context.getSource().getSender();
                    // Brigadier asks for suggestions without applying the node's requirement.
                    if (!sender.hasPermission(othersPermission)) {
                        return suggestions.buildFuture();
                    }
                    return onlinePlayers.getSuggestions(context, suggestions);
                })
                .executes(context -> {
                    CommandSender sender = context.getSource().getSender();
                    String typed = StringArgumentType.getString(context, argument);
                    Player target = OnlinePlayers.find(server, sender, typed);
                    if (target == null) {
                        messages.send(sender, section + ".unknown", Placeholder.unparsed("player", typed));
                    } else {
                        action.run(sender, target);
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static boolean isSelf(CommandSender sender, Player player) {
        return sender instanceof Player self && self.getUniqueId().equals(player.getUniqueId());
    }
}
