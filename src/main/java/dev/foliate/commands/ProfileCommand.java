package dev.foliate.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.profile.ProfileLookup;
import dev.foliate.core.profile.ProfileSummary;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * {@code profile [player]}: shows a player's profile. It is the example of a command with an
 * argument:
 *
 * <ul>
 *   <li>the argument is optional, and its node requires a permission of its own, so that anyone
 *       may see their own profile and only some may see another's;
 *   <li>the online players are suggested while typing;
 *   <li>it reads the profile from the cache, through {@link ProfileLookup}: a command runs on the
 *       region of whoever typed it, so it must never wait for the database;
 *   <li>it reaches the profile feature through an interface of {@code core}, because commands
 *       depend on {@code core} only.
 * </ul>
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
        // which differs from the sender under /execute as.
        node.executes(context -> {
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
                    String typed = suggestions.getRemainingLowerCase();
                    for (Player online : server.getOnlinePlayers()) {
                        if (online.getName().toLowerCase().startsWith(typed)) {
                            suggestions.suggest(online.getName());
                        }
                    }
                    return suggestions.buildFuture();
                })
                .executes(context -> {
                    String typed = StringArgumentType.getString(context, ARGUMENT);
                    Player target = server.getPlayerExact(typed);
                    if (target == null) {
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

    private void show(Audience to, Player player) {
        Optional<ProfileSummary> profile = profiles.lookup(player.getUniqueId());
        if (profile.isEmpty()) {
            messages.send(to, "command.profile.unknown", Placeholder.unparsed("player", player.getName()));
            return;
        }
        ProfileSummary summary = profile.get();
        messages.send(
                to,
                "command.profile.show",
                Placeholder.unparsed("player", summary.name()),
                Placeholder.unparsed("first-seen", DATE_TIME.format(Instant.ofEpochMilli(summary.firstSeen()))),
                Placeholder.unparsed("last-seen", DATE_TIME.format(Instant.ofEpochMilli(summary.lastSeen()))),
                Placeholder.unparsed("joins", String.valueOf(summary.joins())));
    }
}
