package dev.foliate.hook.placeholderapi;

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
     * Turns what PlaceholderAPI returned into a component. Expansions answer with legacy colour
     * codes, written with either {@code &} or the section sign; the text is never parsed as
     * MiniMessage, so a value cannot inject tags.
     */
    static Component toComponent(String value) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(value.replace('\u00A7', '&'));
    }

    @Override
    public String name() {
        return "placeholderapi";
    }

    @Override
    public void enable() {
        FoliateExpansion expansion = new FoliateExpansion(meta, registry);
        if (!expansion.register()) {
            throw new IllegalStateException("PlaceholderAPI refused the expansion");
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
