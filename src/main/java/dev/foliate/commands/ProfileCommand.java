package dev.foliate.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.contracts.profile.ProfileLookup;
import dev.foliate.contracts.profile.ProfileSummary;
import dev.foliate.core.message.MessageService;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * {@code profile [player]}: shows the profile of a player who is online. It is the example of a
 * command with an argument:
 *
 * <ul>
 *   <li>the argument is optional, and its node requires a permission of its own, so that anyone
 *       may see their own profile and only some may see another's (that includes running the
 *       command as someone else, with {@code /execute as});
 *   <li>the online players are suggested while typing;
 *   <li>it reads the profile from the cache, through {@link ProfileLookup}: a command runs on the
 *       region of whoever typed it, so it must never wait for the database;
 *   <li>it reaches the profile feature through an interface of {@code core}, because commands
 *       depend on {@code core} only.
 * </ul>
 *
 * <p>{@link Subcommand} says another player must be reached through the schedulers. Here only
 * their name and unique id are read, which are safe from any thread, and the profile comes from
 * the cache: nothing of the player's body, location or world is touched.
 */
public final class ProfileCommand implements Subcommand {

    private static final String ARGUMENT = "player";
    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final ProfileLookup profiles;
    private final MessageService messages;
    private final Server server;

    public ProfileCommand(ProfileLookup profiles, MessageService messages, Server server) {
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
        return Permissions.COMMAND_PROFILE;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
        // /foliate profile: the one who runs it. The executor is the player the command runs as,
        // which differs from the sender under /execute as: showing another player's profile that
        // way needs the permission to see other players' profiles, like naming them. Without an
        // executor (the console, a command block), there is nobody else's profile to show: the
        // command asks for a name.
        node.requires(source -> source.getExecutor() == null
                        || source.getExecutor() == source.getSender()
                        || source.getSender().hasPermission(Permissions.COMMAND_PROFILE_OTHERS))
                .executes(context -> {
            CommandSourceStack source = context.getSource();
            Entity executor = source.getExecutor();
            if (executor instanceof Player player) {
                show(source.getSender(), player);
            } else {
                messages.send(source.getSender(), "command.profile.name-required");
            }
            return Command.SINGLE_SUCCESS;
        });
        // /foliate profile <player>: a word that is matched against the players online.
        node.then(Commands.argument(ARGUMENT, StringArgumentType.word())
                .requires(source -> source.getSender().hasPermission(Permissions.COMMAND_PROFILE_OTHERS))
                .suggests((context, suggestions) -> {
                    // Brigadier asks for suggestions without applying the node's requirement.
                    if (!context.getSource().getSender().hasPermission(Permissions.COMMAND_PROFILE_OTHERS)) {
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

    /** A player the sender cannot see, such as one who vanished, is treated as not online. */
    private static boolean canSee(CommandSender sender, Player player) {
        return !(sender instanceof Player viewer) || viewer.canSee(player);
    }

    private void show(Audience to, Player player) {
        Optional<ProfileSummary> profile = profiles.lookup(player.getUniqueId());
        if (profile.isEmpty()) {
            // Online, but nothing cached: they have only just joined, or their profile failed to load.
            messages.send(to, "command.profile.not-loaded", Placeholder.unparsed("player", player.getName()));
            return;
        }
        ProfileSummary summary = profile.get();
        messages.send(
                to,
                "command.profile.show",
                Placeholder.unparsed("player", summary.name()),
                Placeholder.unparsed("first-seen", DATE_TIME.format(Instant.ofEpochMilli(summary.firstSeen()))),
                Placeholder.unparsed("since", DATE_TIME.format(Instant.ofEpochMilli(summary.onlineSince()))),
                Placeholder.unparsed("joins", String.valueOf(summary.joins())));
    }
}
