package dev.foliate.metrics;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleSkippedException;
import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Reports anonymous usage statistics to <a href="https://bstats.org">bStats</a>.
 *
 * <p>Off until {@link #PLUGIN_ID} is set: bStats gives each plugin its own id when it is
 * registered on its website, and reporting under someone else's would pollute their numbers. It
 * is an outbound connection, so it can also be turned off in {@code config.yml} and must be
 * mentioned on the plugin's download page.
 */
public final class MetricsModule implements Module {

    /** The id bStats assigned to this plugin, or 0 while it has none. */
    static final int PLUGIN_ID = 0;

    private final JavaPlugin plugin;
    private final ConfigService config;
    private @Nullable Metrics metrics;

    public MetricsModule(JavaPlugin plugin, ConfigService config) {
        this.plugin = plugin;
        this.config = config;
    }

    @Override
    public String name() {
        return "metrics";
    }

    @Override
    public void enable() throws ModuleSkippedException {
        if (PLUGIN_ID == 0) {
            throw new ModuleSkippedException("the plugin has no bStats id yet");
        }
        if (!config.get().metrics()) {
            throw new ModuleSkippedException("metrics are turned off in config.yml");
        }
        metrics = new Metrics(plugin, PLUGIN_ID);
    }

    @Override
    public void disable() {
        if (metrics != null) {
            metrics.shutdown();
            metrics = null;
        }
    }
}
