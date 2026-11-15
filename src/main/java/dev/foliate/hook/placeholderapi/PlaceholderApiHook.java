package dev.foliate.hook.placeholderapi;

import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import io.papermc.paper.plugin.configuration.PluginMeta;
import me.clip.placeholderapi.PlaceholderAPI;
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
        String placeholder = arguments.popOr("The <papi> tag needs a placeholder, as in <papi:player_name>").value();
        OfflinePlayer player = context.target() instanceof OfflinePlayer target ? target : null;
        // Third-party expansions run on the calling thread: PlaceholderAPI gives no guarantee that
        // they are safe on Folia when called from a region that does not own the player.
        String value = PlaceholderAPI.setPlaceholders(player, '%' + placeholder + '%');
        return Tag.selfClosingInserting(LegacyComponentSerializer.legacySection().deserialize(value));
    });
    private @Nullable FoliateExpansion expansion;

    public PlaceholderApiHook(PluginMeta meta, PlaceholderRegistry registry, MessageService messages) {
        this.meta = meta;
        this.registry = registry;
        this.messages = messages;
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
