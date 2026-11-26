package dev.foliate.hooks.zmenu;

import dev.foliate.core.command.Subcommand;
import dev.foliate.core.command.SubcommandProvider;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.scheduler.Schedulers;
import dev.foliate.hooks.zmenu.command.MenuCommand;
import dev.foliate.hooks.zmenu.menu.MenuException;
import dev.foliate.hooks.zmenu.menu.Menus;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.MenuPlugin;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * Menus rendered by zMenu ({@link Menus}), and {@code /foliate menu}, which opens the example one.
 * Only loaded when zMenu is installed: nothing else in the plugin names a class of zMenu.
 */
public final class ZMenuHook implements Module, SubcommandProvider {

    public static final String PLUGIN_NAME = "zMenu";

    /**
     * The module's name. A constant, so that the server test can name the module without loading
     * this class, which would load zMenu's when zMenu is not installed.
     */
    public static final String MODULE_NAME = "zmenu";

    private static final Duration RELOAD_TIMEOUT = Duration.ofSeconds(10);

    private final MessageService messages;
    private final Supplier<InventoryManager> zMenu;
    private final Menus menus;

    public ZMenuHook(Plugin plugin, Schedulers schedulers, MessageService messages) {
        this(plugin, schedulers, messages, () -> {
            if (plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME) instanceof MenuPlugin menuPlugin) {
                return menuPlugin.getInventoryManager();
            }
            throw new IllegalStateException("zMenu is installed but does not expose its API");
        }, RELOAD_TIMEOUT);
    }

    ZMenuHook(
            Plugin plugin,
            Schedulers schedulers,
            MessageService messages,
            Supplier<InventoryManager> zMenu,
            Duration reloadTimeout) {
        this.messages = messages;
        this.zMenu = zMenu;
        this.menus = new Menus(plugin, schedulers, messages, reloadTimeout);
    }

    @Override
    public String name() {
        return MODULE_NAME;
    }

    /** {@code /foliate menu}, which opens the example menu. */
    @Override
    public List<Subcommand> subcommands() {
        return List.of(new MenuCommand(menus::open, messages));
    }

    @Override
    public void enable() throws MenuException {
        menus.load(zMenu.get());
    }

    @Override
    public void reload() throws MenuException {
        menus.reload();
    }

    @Override
    public void disable() {
        menus.unload();
    }

    /** Opens a menu, as {@code /foliate menu} does. For the tests, which live in this package. */
    void open(Player player, String menu) {
        menus.open(player, menu);
    }

    /** Swaps the menus as a reload does on the global region. For the tests, which live in this package. */
    void swapMenus(InventoryManager manager) {
        menus.swapMenus(manager);
    }
}
