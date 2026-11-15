package dev.foliate;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Plugin entrypoint and composition root: everything the plugin is made of is created and wired
 * here, by hand, so the dependency graph can be read in one place.
 *
 * <p>Not final: MockBukkit subclasses the plugin class to load it in tests.
 */
public class FoliatePlugin extends JavaPlugin {

    @Override
    public void onEnable() {
        getSLF4JLogger().info("Foliate {} enabled", getPluginMeta().getVersion());
    }
}
