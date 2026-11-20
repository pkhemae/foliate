package dev.foliate.hooks.zmenu;

import dev.foliate.contracts.menu.MenuException;
import dev.foliate.contracts.menu.Menus;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import fr.maxlego08.menu.api.Inventory;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.MenuPlugin;
import fr.maxlego08.menu.api.exceptions.InventoryException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
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
    private final Supplier<InventoryManager> zMenu;
    private volatile @Nullable InventoryManager manager;
    private volatile Map<String, Inventory> menus = Map.of();

    public ZMenuHook(Plugin plugin, Schedulers schedulers, MessageService messages) {
        this(plugin, schedulers, messages, () -> {
            if (plugin.getServer().getPluginManager().getPlugin(PLUGIN_NAME) instanceof MenuPlugin menuPlugin) {
                return menuPlugin.getInventoryManager();
            }
            throw new IllegalStateException("zMenu is installed but does not expose its API");
        });
    }

    ZMenuHook(Plugin plugin, Schedulers schedulers, MessageService messages, Supplier<InventoryManager> zMenu) {
        this.plugin = plugin;
        this.schedulers = schedulers;
        this.messages = messages;
        this.zMenu = zMenu;
    }

    @Override
    public String name() {
        return "zmenu";
    }

    @Override
    public void enable() throws MenuException {
        InventoryManager manager = zMenu.get();
        // Set before loading: if a menu fails, disable() is called and removes the ones before it.
        this.manager = manager;
        menus = load(manager);
    }

    @Override
    public void reload() throws MenuException {
        InventoryManager manager = this.manager;
        if (manager == null) {
            return;
        }
        // Loading a menu fires a zMenu event, which the server only accepts from a tick thread:
        // do the swap on the global region and wait for it here, off the tick threads.
        AtomicBoolean abandoned = new AtomicBoolean();
        CompletableFuture<Void> swap = schedulers.global().run(() -> {
            if (!abandoned.get()) {
                swapMenus(manager);
            }
        });
        try {
            swap.get(RELOAD_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            // Without this the swap would still happen later, after we reported a failure.
            abandoned.set(true);
            throw new MenuException("the server was too busy to reload the menus, try again", e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MenuLoadFailure failure) {
                throw failure.asMenuException();
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MenuException("interrupted while reloading the menus", e);
        }
    }

    @Override
    public void disable() {
        InventoryManager manager = this.manager;
        if (manager != null) {
            manager.deleteInventories(plugin);
            menus = Map.of();
            this.manager = null;
        }
    }

    @Override
    public void open(Player player, String menu) {
        try {
            schedulers.entity(player).executeOrRun(() -> {
                InventoryManager manager = this.manager;
                Inventory inventory = menus.get(menu);
                if (manager == null) {
                    messages.send(player, "menu.unavailable");
                } else if (inventory == null) {
                    messages.send(player, "menu.unknown", Placeholder.unparsed("menu", menu));
                } else {
                    manager.openInventory(player, inventory);
                }
            });
        } catch (EntityRetiredException gone) {
            // The player left: nothing to open.
        }
    }

    /**
     * Replaces the menus with freshly loaded ones, or leaves everything as it was when one of the
     * files is invalid. Must run on a tick thread.
     */
    void swapMenus(InventoryManager manager) {
        Map<String, Inventory> previous = menus;
        try {
            menus = load(manager);
        } catch (MenuException e) {
            throw new MenuLoadFailure(e);
        }
        previous.values().forEach(manager::deleteInventory);
    }

    /** Loads every menu, or none: the ones loaded before a failure are removed again. */
    private Map<String, Inventory> load(InventoryManager manager) throws MenuException {
        Map<String, Inventory> loaded = new LinkedHashMap<>();
        for (String menu : MENUS) {
            String file = "inventories/" + menu + ".yml";
            try {
                loaded.put(menu, manager.loadInventoryOrSaveResource(plugin, file));
            } catch (InventoryException | RuntimeException e) {
                loaded.values().forEach(manager::deleteInventory);
                throw new MenuException(file + " could not be loaded: " + e.getMessage(), e);
            }
        }
        return Map.copyOf(loaded);
    }

    /** Carries a {@link MenuException} out of the task that runs on the global region. */
    private static final class MenuLoadFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        MenuLoadFailure(MenuException cause) {
            super(cause);
        }

        MenuException asMenuException() {
            return (MenuException) getCause();
        }
    }
}
