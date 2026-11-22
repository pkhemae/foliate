package dev.foliate;

import dev.foliate.commands.InfoCommand;
import dev.foliate.commands.ReloadCommand;
import dev.foliate.core.Platform;
import dev.foliate.core.command.RootCommand;
import dev.foliate.core.command.Subcommand;
import dev.foliate.core.command.SubcommandProvider;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.scheduler.FoliaSchedulers;
import dev.foliate.core.scheduler.Schedulers;
import dev.foliate.core.storage.Database;
import dev.foliate.hooks.placeholderapi.PlaceholderApiHook;
import dev.foliate.hooks.zmenu.ZMenuHook;
import dev.foliate.modules.profile.ProfileModule;
import dev.foliate.modules.scoreboard.ScoreboardModule;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.plugin.java.JavaPlugin;
import org.jspecify.annotations.Nullable;

/**
 * Plugin entrypoint and composition root: everything the plugin is made of is created and wired
 * here, by hand, so the dependency graph can be read in one place.
 *
 * <p>Not final: MockBukkit subclasses the plugin class to load it in tests, and the server test
 * build (the {@code serverTest} source set) extends it through {@link #started} and
 * {@link #failedToStart}, which do nothing here.
 */
public class FoliatePlugin extends JavaPlugin {

    /**
     * The SQL scripts of every module that has tables, given to the {@code Database}: a module that
     * brings scripts of its own adds its list here. The test that opens the databases left by
     * earlier versions reads it too, so that it migrates them exactly as the plugin does.
     */
    public static final List<String> MIGRATIONS = ProfileModule.MIGRATIONS;

    private @Nullable FoliaSchedulers schedulers;
    private @Nullable ModuleManager modules;

    @Override
    public void onEnable() {
        try {
            start();
        } catch (RuntimeException | Error e) {
            failedToStart("the plugin failed to start: " + e);
            throw e;
        }
    }

    private void start() {
        FoliaSchedulers schedulers = new FoliaSchedulers(this);
        ModuleManager modules = new ModuleManager(getSLF4JLogger());
        this.schedulers = schedulers;
        this.modules = modules;

        // Register modules here, dependencies first.
        ConfigService<PluginConfig> config = PluginConfig.service(getDataPath(), getSLF4JLogger());
        MessageService messages = new MessageService(getDataPath(), this::getResource, getSLF4JLogger());
        PlaceholderRegistry placeholders = new PlaceholderRegistry(getSLF4JLogger());
        placeholders.register("version", player -> getPluginMeta().getVersion());

        modules.register(config);
        modules.register(messages);
        hook(modules, PlaceholderApiHook.PLUGIN_NAME, () -> new PlaceholderApiHook(getPluginMeta(), placeholders, messages));
        Database database = new Database(getDataPath(), () -> config.get().storage(), MIGRATIONS, getSLF4JLogger());
        modules.register(database);
        modules.register(new ProfileModule(
                this, database, messages, placeholders, getSLF4JLogger(), Clock.systemUTC()));
        modules.register(new ScoreboardModule(this, schedulers, () -> config.get().scoreboard(), messages));
        hook(modules, ZMenuHook.PLUGIN_NAME, () -> new ZMenuHook(this, schedulers, messages));

        if (!modules.enableAll()) {
            failedToStart("a required module failed to enable");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        // One class per subcommand. The plugin's own are listed here; a feature's come from its
        // module, and only while it runs.
        List<Subcommand> subcommands = new ArrayList<>();
        subcommands.add(new InfoCommand(modules, messages, getPluginMeta().getVersion(), Platform.describe()));
        subcommands.add(new ReloadCommand(this, modules, messages, schedulers, getSLF4JLogger()));
        List<SubcommandProvider> features = modules.enabledOf(SubcommandProvider.class);
        for (SubcommandProvider feature : features) {
            subcommands.addAll(feature.subcommands());
        }
        // What /foliate does typed alone, if a feature gives it: nothing in the template does.
        RootCommand command = new RootCommand("foliate", SubcommandProvider.bareCommandOf(features), subcommands);
        // Built once now: a mistake in a subcommand's build() stops the start here, not in the
        // handler below, which the server runs later and whose failure would leave no command.
        command.build();
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> event.registrar()
                .register(command.build(), "Shows information about Foliate and reloads it."));

        started(new Parts(schedulers, modules, database));
    }

    /** What the plugin is made of, handed to {@link #started}. */
    protected record Parts(Schedulers schedulers, ModuleManager modules, Database database) {}

    /**
     * Called at the end of {@code onEnable}, once every module is enabled and the command is
     * registered. Does nothing: the server test build overrides it to start its checks.
     */
    protected void started(Parts parts) {}

    /**
     * Called when the plugin cannot start, just before it gives up. Does nothing: the server test
     * build overrides it, so that the smoke test fails at once instead of waiting for a verdict
     * that cannot come.
     */
    protected void failedToStart(String reason) {}

    /**
     * Registers an optional integration, only when the other plugin is installed. The hook is
     * created inside the supplier so that none of its classes is touched otherwise, and a hook
     * that does not link against the installed version is left out instead of stopping the plugin.
     */
    private void hook(ModuleManager modules, String plugin, Supplier<? extends Module> factory) {
        if (!getServer().getPluginManager().isPluginEnabled(plugin)) {
            return;
        }
        try {
            modules.register(factory.get());
        } catch (LinkageError e) {
            getSLF4JLogger().error("The {} integration does not work with the installed version and stays off", plugin, e);
        }
    }

    @Override
    public void onDisable() {
        // Nothing scheduled from here on runs (see Schedulers): what is left runs synchronously,
        // here, and must not touch the world. The regions have stopped ticking only when Folia stops; when the plugin
        // alone is disabled (a plugin manager, Paper's /reload, a failed start), a task or event of
        // ours already under way may still be running, see Module#disable. Timers go first, so
        // none starts again while a module is releasing its resources.
        if (schedulers != null) {
            schedulers.shutdown();
        }
        if (modules != null) {
            modules.disableAll();
        }
    }
}
