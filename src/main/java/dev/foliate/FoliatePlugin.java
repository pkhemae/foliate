package dev.foliate;

import dev.foliate.command.FoliateCommand;
import dev.foliate.core.Platform;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.scheduler.Schedulers;
import dev.foliate.hook.placeholderapi.PlaceholderApiHook;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
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
        MessageService messages = new MessageService(getDataPath(), this::getResource, config, getSLF4JLogger());
        PlaceholderRegistry placeholders = new PlaceholderRegistry();
        placeholders.register("version", player -> getPluginMeta().getVersion());

        modules.register(config);
        modules.register(messages);
        hook(modules, PlaceholderApiHook.PLUGIN_NAME, () -> new PlaceholderApiHook(getPluginMeta(), placeholders, messages));

        if (!modules.enableAll()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        FoliateCommand command = new FoliateCommand(
                modules, messages, schedulers, getSLF4JLogger(), getPluginMeta().getVersion(), Platform.describe());
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar()
                .register(command.build(), "Shows information about Foliate and reloads it."));
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
