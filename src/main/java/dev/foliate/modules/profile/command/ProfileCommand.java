package dev.foliate.modules.profile.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.core.command.PlayerTarget;
import dev.foliate.core.command.Subcommand;
import dev.foliate.core.message.MessageService;
import dev.foliate.modules.profile.PlayerProfile;
import dev.foliate.modules.profile.ProfileModule;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/**
 * {@code profile [player]}: shows the profile of a player who is online. It is the example of a
 * command about a player, yourself or another:
 *
 * <ul>
 *   <li>{@link PlayerTarget} works out who it is about and makes every check: anyone may see their
 *       own profile, and only those with {@link #PERMISSION_OTHERS} another's, by name or by
 *       running the command as them with {@code /execute as}. It also suggests the players online.
 *       This class only says what to show;
 *   <li>it reads the profile from the cache: a command runs on the region of whoever typed it,
 *       so it must never wait for the database;
 *   <li>it lives with the feature it shows, in the module's {@code command} package, and the
 *       module offers it ({@link ProfileModule#subcommands()}): it exists exactly when profiles
 *       are loaded.
 * </ul>
 *
 * <p>{@link Subcommand} says another player must be reached through the schedulers. Here only
 * their name and unique id are read, which are safe from any thread, and the profile comes from
 * the cache: nothing of the player's body, location or world is touched.
 */
public final class ProfileCommand implements Subcommand {

    /** To see one's own profile. Declared in the plugin.yml with the other nodes. */
    static final String PERMISSION = "foliate.command.profile";

    /** To see another player's profile, by name or by running the command as them. */
    static final String PERMISSION_OTHERS = "foliate.command.profile.others";

    private static final DateTimeFormatter DATE_TIME =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'").withZone(ZoneOffset.UTC);

    private final Function<UUID, Optional<PlayerProfile>> profiles;
    private final MessageService messages;
    private final PlayerTarget target;

    /** @param profiles the cached profile of an online player, as {@link ProfileModule#find} gives it */
    public ProfileCommand(Function<UUID, Optional<PlayerProfile>> profiles, MessageService messages, Server server) {
        this.profiles = profiles;
        this.messages = messages;
        this.target = new PlayerTarget(server, messages, PERMISSION_OTHERS, "command.profile");
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
        target.addTo(node, this::show);
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
