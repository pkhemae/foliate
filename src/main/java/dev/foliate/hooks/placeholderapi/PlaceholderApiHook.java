package dev.foliate.hooks.placeholderapi;

import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import io.papermc.paper.plugin.configuration.PluginMeta;
import me.clip.placeholderapi.PlaceholderAPI;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

/**
 * Bridges the plugin and PlaceholderAPI in both directions:
 *
 * <ul>
 *   <li>outgoing: everything in the {@link PlaceholderRegistry} becomes {@code %foliate_<name>%};
 *   <li>incoming: any PlaceholderAPI placeholder can be used in the plugin's messages with
 *       {@code <papi:player_name>}.
 * </ul>
 *
 * <p>Third-party expansions are evaluated on whichever thread renders the message, and
 * PlaceholderAPI gives no guarantee that they are safe on Folia away from the player's region:
 * render a message that may contain {@code <papi>} for a player from that player's own scheduler.
 *
 * <p>This is the pattern for every optional integration: the hook is a {@link Module} registered
 * only when the other plugin is installed, and it implements nothing but our own interface, so no
 * third-party class is loaded unless the hook is created. Classes that extend third-party types
 * (here {@link FoliateExpansion}) stay behind it.
 */
public final class PlaceholderApiHook implements Module {

    public static final String PLUGIN_NAME = "PlaceholderAPI";

    /** {@code &} codes, with both hexadecimal notations: {@code &#rrggbb} and {@code &x&r&r&g&g&b&b}. */
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.builder()
            .character('&')
            .hexCharacter('#')
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private final PluginMeta meta;
    private final PlaceholderRegistry registry;
    private final MessageService messages;

    private final TagResolver papiTag = TagResolver.resolver("papi", (arguments, context) -> {
        // MiniMessage splits tag arguments on ':', which a placeholder may contain
        // (%server_time_HH:mm%): put the pieces back together.
        StringBuilder placeholder = new StringBuilder(
                arguments.popOr("The <papi> tag needs a placeholder, as in <papi:player_name>").value());
        while (arguments.hasNext()) {
            placeholder.append(':').append(arguments.pop().value());
        }
        OfflinePlayer player = context.target() instanceof OfflinePlayer target ? target : null;
        return Tag.selfClosingInserting(toComponent(PlaceholderAPI.setPlaceholders(player, "%" + placeholder + "%")));
    });
    private @Nullable FoliateExpansion expansion;

    public PlaceholderApiHook(PluginMeta meta, PlaceholderRegistry registry, MessageService messages) {
        this.meta = meta;
        this.registry = registry;
        this.messages = messages;
    }

    /**
     * Turns what PlaceholderAPI returned into a component. The text is never parsed as MiniMessage,
     * so a value cannot inject tags.
     *
     * <p>Legacy colour codes are honoured with the section sign and with {@code &}: PlaceholderAPI
     * returns values as the expansion gives them, without translating anything, and the most common
     * coloured values (a rank prefix from a permissions plugin, through {@code %vault_prefix%} or
     * {@code %luckperms_prefix%}) are stored and returned with {@code &} codes. The price is the
     * one every Bukkit plugin pays with {@code translateAlternateColorCodes('&', ...)}: an {@code &}
     * followed by a code character is a code, so {@code "R&D"} loses its {@code D} to light purple.
     * An {@code &} followed by anything else ({@code "Tom & Jerry"}) stays as text.
     */
    static Component toComponent(String value) {
        return LEGACY.deserialize(value.replace('\u00A7', '&'));
    }

    @Override
    public String name() {
        return "placeholderapi";
    }

    /**
     * @throws PlaceholderApiException when PlaceholderAPI refuses the expansion: a problem of the
     *     server's setup, such as another expansion with the same identifier, not a bug
     */
    @Override
    public void enable() throws PlaceholderApiException {
        FoliateExpansion expansion = new FoliateExpansion(meta, registry);
        if (!expansion.register()) {
            throw new PlaceholderApiException("PlaceholderAPI refused the expansion '" + expansion.getIdentifier()
                    + "': another expansion may already use that identifier");
        }
        this.expansion = expansion;
        messages.addSharedResolver(papiTag);
    }

    @Override
    public void disable() {
        messages.removeSharedResolver(papiTag);
        if (expansion != null) {
            expansion.unregister();
            expansion = null;
        }
    }
}
