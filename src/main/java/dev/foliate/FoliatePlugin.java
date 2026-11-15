package dev.foliate;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.Schedulers;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Plugin entrypoint and composition root: everything the plugin is made of is created and wired
 * here, by hand, so the dependency graph can be read in one place.
 *
 * <p>Not final: MockBukkit subclasses the plugin class to load it in tests.
 */
public class FoliatePlugin extends JavaPlugin {

    private @Nullable Schedulers schedulers;
    private @Nullable ModuleManager modules;

    @Override
    public void onEnable() {
        Schedulers schedulers = new Schedulers(this);
        ModuleManager modules = new ModuleManager(getSLF4JLogger());
        this.schedulers = schedulers;
        this.modules = modules;

        // Register modules here, dependencies first.
        ConfigService config = new ConfigService(getDataPath(), getSLF4JLogger());
        modules.register(config);

        if (!modules.enableAll()) {
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        // Region tickers are already stopped and nothing can be scheduled any more: what is left
        // runs synchronously and must not touch the world. Timers go first, so none fires while a
        // module is releasing its resources.
        if (schedulers != null) {
            schedulers.shutdown();
        }
        if (modules != null) {
            modules.disableAll();
        }
    }
}
