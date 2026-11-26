package dev.foliate.hooks.zmenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.ManualSchedulers;
import dev.foliate.hooks.zmenu.menu.MenuException;
import fr.maxlego08.menu.api.Inventory;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.exceptions.InventoryException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/**
 * Covers the hook as a module, against a fake zMenu: a reload and a disable that meet under the
 * module manager. The menus themselves are covered by {@code MenusTest}.
 */
class ZMenuHookTest {

    @TempDir
    Path directory;

    private Plugin plugin;
    private MessageService messages;
    private boolean zMenuRejectsFiles;
    /** zMenu's lookup fails, as it does when a reload changes its list while it is read. */
    private volatile boolean zMenuLookupFails;
    /** How long loading a menu takes in the fake zMenu. */
    private volatile Duration loadTime = Duration.ZERO;
    private final List<Inventory> registered = new ArrayList<>();
    private final List<String> opened = new ArrayList<>();
    private final List<Inventory> openedMenus = new ArrayList<>();
    /** Counted down when the fake zMenu starts loading a menu. */
    private volatile CountDownLatch loading = new CountDownLatch(1);

    @BeforeEach
    void setUp() throws Exception {
        MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        MessageService messages = new MessageService(directory, getClass().getClassLoader()::getResourceAsStream, NOPLogger.NOP_LOGGER);
        messages.enable();
        this.plugin = plugin;
        this.messages = messages;
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    /**
     * A server stopping during a reload: the global region no longer ticks, so the swap can never
     * run. Disabling interrupts the wait instead of sitting out the reload timeout.
     */
    @Test
    void disablingDuringAReloadDoesNotWaitForTheTimeout() throws Exception {
        ManualSchedulers halted = new ManualSchedulers();
        ZMenuHook stuck = new ZMenuHook(plugin, halted, messages, this::fakeZMenu, Duration.ofMinutes(5));
        ModuleManager modules = new ModuleManager(NOPLogger.NOP_LOGGER);
        modules.register(stuck);
        modules.enableAll();
        CompletableFuture<Map<String, Throwable>> reload = new CompletableFuture<>();
        Thread reloading = new Thread(() -> reload.complete(modules.reloadAll()));
        reloading.start();
        while (halted.pendingTasks() == 0) {
            Thread.onSpinWait();
        }

        assertTimeoutPreemptively(Duration.ofSeconds(5), modules::disableAll);

        Throwable failure = reload.get(5, TimeUnit.SECONDS).get(ZMenuHook.MODULE_NAME);
        assertTrue(failure instanceof MenuException && failure.getMessage().contains("nothing changed"), String.valueOf(failure));
        // The swap, had the region ticked after all, would have done nothing.
        halted.tick(1);
        assertEquals(List.of(), registered, "disabling removed the menus, and none was loaded again");
    }

    /**
     * The plugin disabled while the server runs, by a plugin manager, as a reload's swap is already
     * running on the global region: the interrupt cannot stop the swap, so the reload waits for it.
     * Otherwise disable() would delete the menus first, and the swap register fresh ones that stay
     * in zMenu.
     */
    @Test
    void disablingWhileASwapRunsLeavesNoMenuBehind() throws Exception {
        ManualSchedulers schedulers = new ManualSchedulers();
        ZMenuHook live = new ZMenuHook(plugin, schedulers, messages, this::fakeZMenu, Duration.ofSeconds(10));
        ModuleManager modules = new ModuleManager(NOPLogger.NOP_LOGGER);
        modules.register(live);
        modules.enableAll();
        loading = new CountDownLatch(1);
        loadTime = Duration.ofSeconds(1);
        CompletableFuture<Map<String, Throwable>> reload = new CompletableFuture<>();
        new Thread(() -> reload.complete(modules.reloadAll())).start();
        while (schedulers.pendingTasks() == 0) {
            Thread.onSpinWait();
        }
        // The global region, still ticking, starts the swap.
        Thread region = new Thread(() -> schedulers.tick(1));
        region.start();
        assertTrue(loading.await(5, TimeUnit.SECONDS));

        modules.disableAll();
        region.join();

        reload.get(5, TimeUnit.SECONDS);
        assertEquals(List.of(), registered, "a menu was registered in zMenu after the module was disabled");
    }

    /** A menu as zMenu names it: after its file, without the extension. */
    @SuppressWarnings("ReferenceEquality") // a proxy is equal to itself only, as Object is
    private Inventory menu(String fileName) {
        return (Inventory) Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {Inventory.class},
                (self, call, parameters) -> switch (call.getName()) {
                    case "equals" -> self == parameters[0];
                    case "hashCode" -> System.identityHashCode(self);
                    case "getFileName" -> fileName;
                    default -> "inventory";
                });
    }

    /** Implements only the handful of zMenu methods the hook calls. */
    @SuppressWarnings("ReferenceEquality") // a proxy is equal to itself only, as Object is
    private InventoryManager fakeZMenu() {
        return (InventoryManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {InventoryManager.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "loadInventoryOrSaveResource" -> {
                            loading.countDown();
                            Thread.sleep(loadTime.toMillis());
                            if (zMenuRejectsFiles) {
                                throw new InventoryException("size must be a multiple of 9");
                            }
                            String file = Path.of((String) arguments[1]).getFileName().toString();
                            Inventory inventory = menu(file.substring(0, file.length() - ".yml".length()));
                            registered.add(inventory);
                            return inventory;
                        }
                        case "getInventories" -> {
                            return List.copyOf(registered);
                        }
                        case "getInventory" -> {
                            if (zMenuLookupFails) {
                                throw new ConcurrentModificationException();
                            }
                            return registered.stream()
                                    .filter(menu -> menu.getFileName().equalsIgnoreCase((String) arguments[1]))
                                    .findFirst();
                        }
                        case "deleteInventory" -> registered.remove(arguments[0]);
                        case "deleteInventories" -> registered.clear();
                        case "openInventory" -> {
                            opened.add(((Player) arguments[0]).getName());
                            openedMenus.add((Inventory) arguments[1]);
                        }
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
    }
}
