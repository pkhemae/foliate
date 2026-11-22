package dev.foliate.hooks.zmenu;

import dev.foliate.core.command.Subcommand;
import dev.foliate.core.command.SubcommandProvider;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import dev.foliate.hooks.zmenu.command.MenuCommand;
import fr.maxlego08.menu.api.Inventory;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.MenuPlugin;
import fr.maxlego08.menu.api.exceptions.InventoryException;
import java.io.Serial;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 *
 * <p>A menu is looked up in zMenu each time it is opened, by this plugin and its file name, never
 * kept as an object: {@code /zm reload inventory <name>} replaces it with a new one and deletes the
 * one this hook loaded.
 */
public final class ZMenuHook implements Module, SubcommandProvider {

    public static final String PLUGIN_NAME = "zMenu";

    /**
     * The module's name. A constant, so that the server test can name the module without loading
     * this class, which would load zMenu's when zMenu is not installed.
     */
    public static final String MODULE_NAME = "zmenu";

    /** File names under {@code inventories/}, without the extension. */
    private static final List<String> MENUS = List.of("example");

    private static final Duration RELOAD_TIMEOUT = Duration.ofSeconds(10);

    private final Plugin plugin;
    private final Schedulers schedulers;
    private final MessageService messages;
    private final Supplier<InventoryManager> zMenu;
    private final Duration reloadTimeout;
    private volatile @Nullable InventoryManager manager;

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
        return MODULE_NAME;
    }

    /** {@code /foliate menu}, which opens the example menu. */
    @Override
    public List<Subcommand> subcommands() {
        return List.of(new MenuCommand(this::open, messages));
    }

    @Override
    public void enable() throws MenuException {
        InventoryManager manager = zMenu.get();
        // Set before loading: if a menu fails, disable() is called and removes the ones before it.
        this.manager = manager;
        load(manager);
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
            throw swapFailure(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (claimed.compareAndSet(false, true)) {
                throw new MenuException("interrupted before the menus were reloaded: nothing changed", e);
            }
            // The swap is already running on a tick thread, which the interrupt cannot stop: wait
            // for it, so that the module is never disabled while it is still registering menus.
            // Its outcome is the reload's. It ends with its tick, so this cannot hang.
            try {
                swap.join();
            } catch (CompletionException failed) {
                throw swapFailure(failed.getCause());
            }
        }
    }

    /** The failure of the swap task, as the reload reports it. */
    private static MenuException swapFailure(@Nullable Throwable cause) {
        if (cause instanceof MenuLoadFailure failure) {
            return failure.asMenuException();
        }
        throw new IllegalStateException(cause);
    }

    @Override
    public void disable() {
        InventoryManager manager = this.manager;
        if (manager != null) {
            // Forgotten before they are deleted: when the plugin is disabled while the server
            // runs, a player may be opening a menu on their own thread right now, and from here on
            // is told menus are unavailable instead. Deleting only touches zMenu's registry.
            this.manager = null;
            manager.deleteInventories(plugin);
        }
    }

    /**
     * Opens a menu. Safe to call from any thread: the menu is opened from the player's own region.
     * The player is told when the menu does not exist or menus are not loaded.
     *
     * @param menu the file name of the menu under {@code inventories/}, without the extension
     */
    public void open(Player player, String menu) {
        try {
            schedulers.entity(player).executeOrRun(() -> {
                InventoryManager manager = this.manager;
                if (manager == null) {
                    messages.send(player, "zmenu.unavailable");
                    return;
                }
                // As zMenu has it now, which a /zm reload may have replaced since it was loaded.
                Optional<Inventory> inventory;
                try {
                    inventory = MENUS.contains(menu) ? manager.getInventory(plugin, menu) : Optional.empty();
                } catch (RuntimeException e) {
                    // zMenu keeps the menus in a plain list, which a reload on the global region
                    // may be changing right now: the player can simply try again.
                    plugin.getSLF4JLogger().debug("Looking up the menu '{}' in zMenu failed", menu, e);
                    messages.send(player, "zmenu.unavailable");
                    return;
                }
                if (inventory.isPresent()) {
                    manager.openInventory(player, inventory.get());
                } else {
                    messages.send(player, "zmenu.unknown", Placeholder.unparsed("menu", menu));
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
        // The menus as zMenu has them now: a /zm reload may have replaced the ones loaded here.
        List<Inventory> previous = List.copyOf(manager.getInventories(plugin));
        try {
            load(manager);
        } catch (MenuException e) {
            throw new MenuLoadFailure(e);
        }
        previous.forEach(manager::deleteInventory);
    }

    /** Loads every menu, or none: the ones loaded before a failure are removed again. */
    private void load(InventoryManager manager) throws MenuException {
        List<Inventory> loaded = new ArrayList<>();
        for (String menu : MENUS) {
            String file = "inventories/" + menu + ".yml";
            try {
                loaded.add(manager.loadInventoryOrSaveResource(plugin, file));
            } catch (InventoryException | RuntimeException e) {
                loaded.forEach(manager::deleteInventory);
                throw new MenuException(file + " could not be loaded: " + e.getMessage(), e);
            }
        }
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
