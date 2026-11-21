package dev.foliate.hooks.zmenu;

import dev.foliate.contracts.menu.Menus;
import dev.foliate.core.command.Subcommand;
import dev.foliate.core.command.SubcommandProvider;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import fr.maxlego08.menu.api.Inventory;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.MenuPlugin;
import fr.maxlego08.menu.api.exceptions.InventoryException;
import java.io.Serial;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
public final class ZMenuHook implements Module, Menus, SubcommandProvider {

    public static final String PLUGIN_NAME = "zMenu";

    /** File names under {@code inventories/}, without the extension. */
    private static final List<String> MENUS = List.of("example");

    private static final Duration RELOAD_TIMEOUT = Duration.ofSeconds(10);

    private final Plugin plugin;
    private final Schedulers schedulers;
    private final MessageService messages;
    private final Supplier<InventoryManager> zMenu;
    private final Duration reloadTimeout;
    private volatile @Nullable InventoryManager manager;
    private volatile Map<String, Inventory> menus = Map.of();

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
        this.plugin = plugin;
        this.schedulers = schedulers;
        this.messages = messages;
        this.zMenu = zMenu;
        this.reloadTimeout = reloadTimeout;
    }

    @Override
    public String name() {
        return "zmenu";
    }

    /** {@code /foliate menu}, which opens the example menu. */
    @Override
    public List<Subcommand> subcommands() {
        return List.of(new MenuCommand(this));
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
        // do the swap on the global region and wait for it here, off the tick threads. Whoever
        // claims the swap first decides: the task, which then does it, or this thread giving up
        // (timeout, interruption), which then means it never happens. Checking a flag instead
        // would let a task that read it just before we gave up swap the menus after the reload
        // was reported as failed.
        AtomicBoolean claimed = new AtomicBoolean();
        CompletableFuture<Void> swap = schedulers.global().run(() -> {
            if (claimed.compareAndSet(false, true)) {
                swapMenus(manager);
            }
        });
        try {
            try {
                swap.get(reloadTimeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                if (claimed.compareAndSet(false, true)) {
                    throw new MenuException("the server was too busy to reload the menus, try again", e);
                }
                // The swap started in time and is running on a tick thread: its outcome is the
                // reload's. It ends with its tick, so this cannot hang.
                swap.get();
            }
        } catch (ExecutionException e) {
            if (e.getCause() instanceof MenuLoadFailure failure) {
                throw failure.asMenuException();
            }
            throw new IllegalStateException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MenuException(
                    claimed.compareAndSet(false, true)
                            ? "interrupted before the menus were reloaded: nothing changed"
                            : "interrupted while the menus were being reloaded: check them again in a moment",
                    e);
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

        @Serial
        private static final long serialVersionUID = 1L;

        MenuLoadFailure(MenuException cause) {
            super(cause);
        }

        MenuException asMenuException() {
            return (MenuException) Objects.requireNonNull(getCause());
        }
    }
}
