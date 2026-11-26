package dev.foliate.hooks.zmenu.menu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.message.MessageService;
import dev.foliate.core.scheduler.FoliaSchedulers;
import dev.foliate.core.scheduler.ManualSchedulers;
import fr.maxlego08.menu.api.Inventory;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.exceptions.InventoryException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * Covers the menus's own bookkeeping against a fake zMenu. That zMenu accepts our files and draws
 * the menu is checked on a real server.
 */
class MenusTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private Plugin plugin;
    private MessageService messages;
    private Menus menus;
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
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        MessageService messages = new MessageService(directory, getClass().getClassLoader()::getResourceAsStream, NOPLogger.NOP_LOGGER);
        messages.enable();
        this.plugin = plugin;
        this.messages = messages;
        menus = new Menus(plugin, new FoliaSchedulers(plugin), messages, Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void enablingLoadsTheMenus() throws Exception {
        menus.load(fakeZMenu());

        assertEquals(1, registered.size());
    }

    @Test
    void swappingReplacesTheOldMenusWithFreshOnes() throws Exception {
        menus.load(fakeZMenu());
        Inventory before = registered.getFirst();

        menus.swapMenus(fakeZMenu());

        assertEquals(1, registered.size());
        assertNotSame(before, registered.getFirst());
    }

    @Test
    void failedSwapKeepsThePreviousMenusUsable() throws Exception {
        menus.load(fakeZMenu());
        Inventory before = registered.getFirst();
        PlayerMock player = server.addPlayer("Alex");

        zMenuRejectsFiles = true;
        assertThrows(RuntimeException.class, () -> menus.swapMenus(fakeZMenu()));
        menus.open(player, "example");
        server.getScheduler().performTicks(1);

        assertEquals(List.of(before), registered);
        assertEquals(List.of("Alex"), opened);
    }

    @Test
    void failingToEnableLeavesNothingBehindAndTellsPlayers() {
        zMenuRejectsFiles = true;
        PlayerMock player = server.addPlayer();

        assertThrows(MenuException.class, () -> menus.load(fakeZMenu()));
        menus.unload();
        menus.open(player, "example");
        server.getScheduler().performTicks(1);

        assertTrue(registered.isEmpty());
        assertTrue(opened.isEmpty());
        assertTrue(plain(player).contains("not available"));
    }

    /**
     * zMenu keeps the menus in a plain list: a lookup while a reload changes it on the global
     * region can fail. The player is told menus are unavailable, instead of nothing happening.
     */
    @Test
    void failedLookupTellsThePlayerMenusAreUnavailable() throws Exception {
        menus.load(fakeZMenu());
        PlayerMock player = server.addPlayer();
        zMenuLookupFails = true;

        menus.open(player, "example");
        server.getScheduler().performTicks(1);

        assertTrue(opened.isEmpty());
        assertTrue(plain(player).contains("not available"));
    }

    @Test
    void unknownMenuIsReportedToThePlayer() throws Exception {
        menus.load(fakeZMenu());
        PlayerMock player = server.addPlayer();

        menus.open(player, "missing");
        server.getScheduler().performTicks(1);

        assertTrue(plain(player).contains("missing"));
    }

    @Test
    void disablingRemovesTheMenus() throws Exception {
        menus.load(fakeZMenu());

        menus.unload();

        assertTrue(registered.isEmpty());
    }

    /**
     * {@code /zm reload inventory example} replaces the menu with a new object: the one the menus
     * loaded is deleted, and opening it would show a menu zMenu no longer knows.
     */
    @Test
    void menuReloadedByZMenuIsOpenedAsZMenuHasItNow() throws Exception {
        menus.load(fakeZMenu());
        Inventory reloaded = zMenuReloads(registered.getFirst());
        PlayerMock player = server.addPlayer("Alex");

        menus.open(player, "example");
        server.getScheduler().performTicks(1);

        assertEquals(List.of(reloaded), openedMenus);
    }

    @Test
    void swapAfterZMenuReloadedAMenuLeavesASingleCopy() throws Exception {
        menus.load(fakeZMenu());
        zMenuReloads(registered.getFirst());

        menus.swapMenus(fakeZMenu());

        assertEquals(1, registered.size());
    }

    /** A reload that gave up waiting must not swap the menus when its task finally runs. */
    @Test
    void reloadThatTimedOutBeforeTheSwapStartedNeverSwaps() throws Exception {
        ManualSchedulers schedulers = new ManualSchedulers();
        Menus slow = new Menus(plugin, schedulers, messages, Duration.ofMillis(50));
        slow.load(fakeZMenu());
        Inventory before = registered.getFirst();

        MenuException failure = assertThrows(MenuException.class, slow::reload);
        schedulers.tick(1);

        assertTrue(failure.getMessage().contains("too busy"), failure.getMessage());
        assertEquals(List.of(before), registered);
    }

    /**
     * A swap that started just before the timeout cannot be called off: the reload waits for it
     * and reports what it did, instead of a failure followed by fresh menus.
     */
    @Test
    void swapThatStartedBeforeTheTimeoutIsWaitedFor() throws Exception {
        ManualSchedulers schedulers = new ManualSchedulers();
        Menus slow = new Menus(plugin, schedulers, messages, Duration.ofSeconds(1));
        slow.load(fakeZMenu());
        Inventory before = registered.getFirst();
        // Longer than the timeout: the reload gives up waiting while the swap runs.
        loadTime = Duration.ofMillis(1500);

        CompletableFuture<Void> reload = CompletableFuture.runAsync(() -> {
            try {
                slow.reload();
            } catch (MenuException e) {
                throw new CompletionException(e);
            }
        });
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (schedulers.pendingTasks() == 0 && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        // Runs the swap here, on the test thread; it outlasts the reload's timeout.
        schedulers.tick(1);

        reload.get(10, TimeUnit.SECONDS);
        assertEquals(1, registered.size());
        assertNotSame(before, registered.getFirst());
    }

    /** An interrupted reload is a reload given up on: its task must not swap the menus later. */
    @Test
    void interruptedReloadNeverSwaps() throws Exception {
        ManualSchedulers schedulers = new ManualSchedulers();
        Menus slow = new Menus(plugin, schedulers, messages, Duration.ofSeconds(10));
        slow.load(fakeZMenu());
        Inventory before = registered.getFirst();

        Thread.currentThread().interrupt();
        MenuException failure = assertThrows(MenuException.class, slow::reload);
        assertTrue(Thread.interrupted(), "the interruption is kept for the caller");
        schedulers.tick(1);

        assertTrue(failure.getMessage().contains("nothing changed"), failure.getMessage());
        assertEquals(List.of(before), registered);
    }

    private static String plain(PlayerMock player) {
        return PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage());
    }

    /** What zMenu's reloadInventory does: deletes the menu and loads its file again. */
    private Inventory zMenuReloads(Inventory menu) {
        registered.remove(menu);
        Inventory fresh = menu(menu.getFileName());
        registered.add(fresh);
        return fresh;
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

    /** Implements only the handful of zMenu methods the menus calls. */
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
