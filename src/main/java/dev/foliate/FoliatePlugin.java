package dev.foliate;

import dev.foliate.command.FoliateCommand;
import dev.foliate.core.Platform;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.menu.Menus;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.scheduler.FoliaSchedulers;
import dev.foliate.core.storage.Database;
import dev.foliate.hook.placeholderapi.PlaceholderApiHook;
import dev.foliate.hook.zmenu.ZMenuHook;
import dev.foliate.metrics.MetricsModule;
import dev.foliate.profile.ProfileModule;
import dev.foliate.selftest.SelfTest;
import dev.foliate.sidebar.SidebarModule;
import dev.foliate.update.GitHubReleases;
import dev.foliate.update.UpdateCheckModule;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.util.function.Supplier;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Plugin entrypoint and composition root: everything the plugin is made of is created and wired
 * here, by hand, so the dependency graph can be read in one place.
 *
 * <p>Not final: MockBukkit subclasses the plugin class to load it in tests.
 */
public class FoliatePlugin extends JavaPlugin {

    /** The id bStats gave this plugin when it was registered on bstats.org, or 0 while it has none. */
    private static final int BSTATS_PLUGIN_ID = 0;

    private @Nullable FoliaSchedulers schedulers;
    private @Nullable ModuleManager modules;

    @Override
    public void onEnable() {
        try {
            start();
        } catch (RuntimeException | Error e) {
            // The server would log this and carry on without the plugin, leaving the smoke test
            // waiting for a verdict that cannot come.
            if (SelfTest.requested()) {
                SelfTest.abort(this, "the plugin failed to start: " + e);
            }
            throw e;
        }
    }

    private void start() {
        FoliaSchedulers schedulers = new FoliaSchedulers(this);
        ModuleManager modules = new ModuleManager(getSLF4JLogger());
        this.schedulers = schedulers;
        this.modules = modules;

        // Register modules here, dependencies first.
        ConfigService config = new ConfigService(getDataPath(), getSLF4JLogger());
        MessageService messages = new MessageService(getDataPath(), this::getResource, config, getSLF4JLogger());
        PlaceholderRegistry placeholders = new PlaceholderRegistry();
        placeholders.register("version", player -> getPluginMeta().getVersion());

        modules.register(config);
        modules.register(messages);
        hook(modules, PlaceholderApiHook.PLUGIN_NAME, () -> new PlaceholderApiHook(getPluginMeta(), placeholders, messages));
        modules.register(new MetricsModule(this, config, BSTATS_PLUGIN_ID));
        modules.register(new UpdateCheckModule(
                config, schedulers, getSLF4JLogger(), getPluginMeta().getVersion(), new GitHubReleases()));
        Database database = new Database(getDataPath(), config, getSLF4JLogger());
        modules.register(database);
        modules.register(new ProfileModule(
                this, database, messages, placeholders, getSLF4JLogger(), Clock.systemUTC()));
        modules.register(new SidebarModule(this, schedulers, config, messages));
        Menus menus = hook(modules, ZMenuHook.PLUGIN_NAME, () -> new ZMenuHook(this, schedulers, messages));

        if (!modules.enableAll()) {
            if (SelfTest.requested()) {
                SelfTest.abort(this, "a required module failed to enable");
            }
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        FoliateCommand command = new FoliateCommand(
                modules, messages, schedulers, getSLF4JLogger(), getPluginMeta().getVersion(), Platform.describe(), menus);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar()
                .register(command.build(), "Shows information about Foliate and reloads it."));

        if (SelfTest.requested()) {
            new SelfTest(this, schedulers, modules, database).start();
        }
    }

    /**
     * Registers an optional integration, only when the other plugin is installed. The hook is
     * created inside the supplier so that none of its classes is touched otherwise, and a hook
     * that does not link against the installed version is left out instead of stopping the plugin.
     *
     * @return the hook, or null when it is not available
     */
    private <T extends Module> @Nullable T hook(ModuleManager modules, String plugin, Supplier<T> factory) {
        if (!getServer().getPluginManager().isPluginEnabled(plugin)) {
            return null;
        }
        try {
            T hook = factory.get();
            modules.register(hook);
            return hook;
        } catch (LinkageError e) {
            getSLF4JLogger().error("The {} integration does not work with the installed version and stays off", plugin, e);
            return null;
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
