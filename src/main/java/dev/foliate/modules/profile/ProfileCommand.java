package dev.foliate.modules.profile;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.core.command.Subcommand;
import dev.foliate.core.message.MessageService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * {@code profile [player]}: shows the profile of a player who is online. It is the example of a
 * command with an argument:
 *
 * <ul>
 *   <li>the argument is optional, and its node requires a permission of its own, so that anyone
 *       may see their own profile and only some may see another's. Running the command as
 *       someone else, with {@code /execute as}, needs the same permission, checked when the
 *       command runs: Brigadier checks requirements before {@code /execute as} changes the
 *       executor;
 *   <li>the online players are suggested while typing;
 *   <li>it reads the profile from the cache: a command runs on the region of whoever typed it,
 *       so it must never wait for the database;
 *   <li>it lives with the feature it shows, in the module's package, and the module offers it
 *       ({@link ProfileModule#subcommands()}): it exists exactly when profiles are loaded.
 * </ul>
 *
 * <p>{@link Subcommand} says another player must be reached through the schedulers. Here only
 * their name and unique id are read, which are safe from any thread, and the profile comes from
 * the cache: nothing of the player's body, location or world is touched.
 */
final class ProfileCommand implements Subcommand {

    /** To see one's own profile. Declared in the plugin.yml with the other nodes. */
    static final String PERMISSION = "foliate.command.profile";

    /** To see another player's profile, by name or by running the command as them. */
    static final String PERMISSION_OTHERS = "foliate.command.profile.others";

    private static final String ARGUMENT = "player";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Function<UUID, Optional<PlayerProfile>> profiles;
    private final MessageService messages;
    private final Server server;

    /** @param profiles the cached profile of an online player, as {@link ProfileModule#find} gives it */
    ProfileCommand(Function<UUID, Optional<PlayerProfile>> profiles, MessageService messages, Server server) {
        this.profiles = profiles;
        this.messages = messages;
        this.server = server;
    }

    @Override
    public String name() {
        return "profile";
    }

    @Override
    public String permission() {
        return PERMISSION;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
        // /foliate profile: the one who runs it. The executor is the player the command runs as,
        // which differs from the sender under /execute as: showing another player's profile that
        // way needs the permission to see other players' profiles, like naming them. It is checked
        // here, when the command runs, and not in requires(): Brigadier evaluates requirements
        // while it parses, with the source as typed, and /execute as only swaps the executor
        // afterwards. Without an executor (the console, a command block), there is nobody else's
        // profile to show: the command asks for a name.
        node.executes(context -> {
            CommandSourceStack source = context.getSource();
            CommandSender sender = source.getSender();
            if (!(source.getExecutor() instanceof Player player)) {
                messages.send(sender, "command.profile.name-required");
            } else if (isSelf(sender, player) || sender.hasPermission(PERMISSION_OTHERS)) {
                show(sender, player);
            } else {
                messages.send(sender, "command.profile.others-denied");
            }
            return Command.SINGLE_SUCCESS;
        });
        // /foliate profile <player>: a word that is matched against the players online.
        node.then(Commands.argument(ARGUMENT, StringArgumentType.word())
                .requires(source -> source.getSender().hasPermission(PERMISSION_OTHERS))
                .suggests((context, suggestions) -> {
                    // Brigadier asks for suggestions without applying the node's requirement.
                    if (!context.getSource().getSender().hasPermission(PERMISSION_OTHERS)) {
                        return suggestions.buildFuture();
                    }
                    CommandSender sender = context.getSource().getSender();
                    String typed = suggestions.getRemainingLowerCase();
                    for (Player online : server.getOnlinePlayers()) {
                        if (canSee(sender, online) && online.getName().toLowerCase(Locale.ROOT).startsWith(typed)) {
                            suggestions.suggest(online.getName());
                        }
                    }
                    return suggestions.buildFuture();
                })
                .executes(context -> {
                    String typed = StringArgumentType.getString(context, ARGUMENT);
                    Player target = server.getPlayerExact(typed);
                    if (target == null || !canSee(context.getSource().getSender(), target)) {
                        messages.send(
                                context.getSource().getSender(),
                                "command.profile.unknown",
                                Placeholder.unparsed("player", typed));
                    } else {
                        show(context.getSource().getSender(), target);
                    }
                    return Command.SINGLE_SUCCESS;
                }));
    }

    private static boolean isSelf(CommandSender sender, Player player) {
        return sender instanceof Player self && self.getUniqueId().equals(player.getUniqueId());
    }

    /** A player the sender cannot see, such as one who vanished, is treated as not online. */
    private static boolean canSee(CommandSender sender, Player player) {
        return !(sender instanceof Player viewer) || viewer.canSee(player);
    }

    private void show(Audience to, Player player) {
        Optional<PlayerProfile> profile = profiles.apply(player.getUniqueId());
        if (profile.isEmpty()) {
            // Online, but nothing cached: they have only just joined, or their profile failed to load.
            messages.send(to, "command.profile.not-loaded", Placeholder.unparsed("player", player.getName()));
            return;
        }
        PlayerProfile shown = profile.get();
        messages.send(
                to,
                "command.profile.show",
                Placeholder.unparsed("player", shown.name()),
                Placeholder.unparsed("first-seen", DATE_TIME.format(Instant.ofEpochMilli(shown.firstSeen()))),
                // While the player is online, lastSeen is the time of this join.
                Placeholder.unparsed("since", DATE_TIME.format(Instant.ofEpochMilli(shown.lastSeen()))),
                Placeholder.unparsed("joins", String.valueOf(shown.joins())));
    }
}
