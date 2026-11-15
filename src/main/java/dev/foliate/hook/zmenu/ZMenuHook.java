package dev.foliate.hook.zmenu;

import dev.foliate.core.menu.Menus;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.scheduler.Schedulers;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.MenuPlugin;
import fr.maxlego08.menu.api.exceptions.InventoryException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;

/**
 * Menus rendered by zMenu. Each menu is a YAML file under {@code inventories/} that administrators
 * can edit; the defaults are copied from the jar on first start.
 *
 * <p>To add a menu, drop its file in {@code src/main/resources/inventories/} and add its name to
 * {@link #MENUS}.
 */
public final class ZMenuHook implements Module, Menus {

    public static final String PLUGIN_NAME = "zMenu";

    /** File names under {@code inventories/}, without the extension. */
    private static final List<String> MENUS = List.of("example");

    private static final long RELOAD_TIMEOUT_SECONDS = 10;

    private final Plugin plugin;
    private final Schedulers schedulers;
    private final MessageService messages;
    private volatile @Nullable InventoryManager inventories;

    public ZMenuHook(Plugin plugin, Schedulers schedulers, MessageService messages) {
        this.plugin = plugin;
        this.schedulers = schedulers;
        this.messages = messages;
    }

    @Override
    public String name() {
        return "zmenu";
    }

    @Override
    public void enable() throws InventoryException {
        if (!(plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME) instanceof MenuPlugin zMenu)) {
            throw new IllegalStateException("zMenu is installed but does not expose its API");
        }
        InventoryManager manager = zMenu.getInventoryManager();
        loadMenus(manager);
        inventories = manager;
    }

    @Override
    public void reload() throws Exception {
        InventoryManager manager = inventories;
        if (manager == null) {
            return;
        }
        // Reloads run on an async thread, but zMenu's registry is not documented as thread-safe:
        // swap the menus on the global region and wait for it here, off the tick threads.
        schedulers.global()
                .supply(() -> {
                    manager.deleteInventories(plugin);
                    try {
                        loadMenus(manager);
                    } catch (InventoryException e) {
                        throw new IllegalStateException(e.getMessage(), e);
                    }
                    return null;
                })
                .get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
    }

    @Override
    public void disable() {
        InventoryManager manager = inventories;
        if (manager != null) {
            manager.deleteInventories(plugin);
            inventories = null;
        }
    }

    @Override
    public void open(Player player, String menu) {
        schedulers.entity(player).executeOrRun(() -> {
            InventoryManager manager = inventories;
            if (manager == null) {
                return;
            }
            manager.getInventory(plugin, menu)
                    .ifPresentOrElse(
                            inventory -> manager.openInventory(player, inventory),
                            () -> messages.send(player, "menu.unknown", Placeholder.unparsed("menu", menu)));
        });
    }

    private void loadMenus(InventoryManager manager) throws InventoryException {
        for (String menu : MENUS) {
            manager.loadInventoryOrSaveResource(plugin, "inventories/" + menu + ".yml");
        }
    }
}
