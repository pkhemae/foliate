package dev.foliate.hook.placeholderapi;

import dev.foliate.core.placeholder.PlaceholderRegistry;
import io.papermc.paper.plugin.configuration.PluginMeta;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jspecify.annotations.Nullable;

/** Exposes the {@link PlaceholderRegistry} to PlaceholderAPI as {@code %foliate_<name>%}. */
final class FoliateExpansion extends PlaceholderExpansion {

    private final PluginMeta meta;
    private final PlaceholderRegistry registry;

    FoliateExpansion(PluginMeta meta, PlaceholderRegistry registry) {
        this.meta = meta;
        this.registry = registry;
    }

    @Override
    public String getIdentifier() {
        return "foliate";
    }

    @Override
    public String getAuthor() {
        return String.join(", ", meta.getAuthors());
    }

    @Override
    public String getVersion() {
        return meta.getVersion();
    }

    /** Without this PlaceholderAPI drops the expansion every time it reloads. */
    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(@Nullable OfflinePlayer player, String params) {
        return registry.resolve(params, player);
    }
}
