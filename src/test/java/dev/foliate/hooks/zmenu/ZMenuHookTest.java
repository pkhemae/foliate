package dev.foliate.hooks.zmenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.contracts.menu.MenuException;
import dev.foliate.core.config.ConfigService;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
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
 * Covers the hook's own bookkeeping against a fake zMenu. That zMenu accepts our files and draws
 * the menu is checked on a real server.
 */
class ZMenuHookTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private Plugin plugin;
    private MessageService messages;
    private ZMenuHook hook;
    private boolean zMenuRejectsFiles;
    /** How long loading a menu takes in the fake zMenu. */
    private volatile Duration loadTime = Duration.ZERO;
    private final List<Inventory> registered = new ArrayList<>();
    private final List<String> opened = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        ConfigService<PluginConfig> config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);
        messages.enable();
        this.plugin = plugin;
        this.messages = messages;
        hook = new ZMenuHook(plugin, new FoliaSchedulers(plugin), messages, this::fakeZMenu, Duration.ofSeconds(10));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void enablingLoadsTheMenus() throws Exception {
        hook.enable();

        assertEquals(1, registered.size());
    }

    @Test
    void swappingReplacesTheOldMenusWithFreshOnes() throws Exception {
        hook.enable();
        Inventory before = registered.getFirst();

        hook.swapMenus(fakeZMenu());

        assertEquals(1, registered.size());
        assertTrue(registered.getFirst() != before);
    }

    @Test
    void failedSwapKeepsThePreviousMenusUsable() throws Exception {
        hook.enable();
        Inventory before = registered.getFirst();
        PlayerMock player = server.addPlayer("Alex");

        zMenuRejectsFiles = true;
        assertThrows(RuntimeException.class, () -> hook.swapMenus(fakeZMenu()));
        hook.open(player, "example");
        server.getScheduler().performTicks(1);

        assertEquals(List.of(before), registered);
        assertEquals(List.of("Alex"), opened);
    }

    @Test
    void failingToEnableLeavesNothingBehindAndTellsPlayers() {
        zMenuRejectsFiles = true;
        PlayerMock player = server.addPlayer();

        assertThrows(MenuException.class, hook::enable);
        hook.disable();
        hook.open(player, "example");
        server.getScheduler().performTicks(1);

        assertTrue(registered.isEmpty());
        assertTrue(opened.isEmpty());
        assertTrue(plain(player).contains("not available"));
    }

    @Test
    void unknownMenuIsReportedToThePlayer() throws Exception {
        hook.enable();
        PlayerMock player = server.addPlayer();

        hook.open(player, "missing");
        server.getScheduler().performTicks(1);

        assertTrue(plain(player).contains("missing"));
    }

    @Test
    void disablingRemovesTheMenus() throws Exception {
        hook.enable();

        hook.disable();

        assertTrue(registered.isEmpty());
    }

    /** A reload that gave up waiting must not swap the menus when its task finally runs. */
    @Test
    void reloadThatTimedOutBeforeTheSwapStartedNeverSwaps() throws Exception {
        ManualSchedulers schedulers = new ManualSchedulers();
        ZMenuHook slow = new ZMenuHook(plugin, schedulers, messages, this::fakeZMenu, Duration.ofMillis(50));
        slow.enable();
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
        ZMenuHook slow = new ZMenuHook(plugin, schedulers, messages, this::fakeZMenu, Duration.ofSeconds(1));
        slow.enable();
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
        assertTrue(registered.getFirst() != before);
    }

    /** An interrupted reload is a reload given up on: its task must not swap the menus later. */
    @Test
    void interruptedReloadNeverSwaps() throws Exception {
        ManualSchedulers schedulers = new ManualSchedulers();
        ZMenuHook slow = new ZMenuHook(plugin, schedulers, messages, this::fakeZMenu, Duration.ofSeconds(10));
        slow.enable();
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

    /** Implements only the handful of zMenu methods the hook calls. */
    private InventoryManager fakeZMenu() {
        return (InventoryManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {InventoryManager.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "loadInventoryOrSaveResource" -> {
                            Thread.sleep(loadTime.toMillis());
                            if (zMenuRejectsFiles) {
                                throw new InventoryException("size must be a multiple of 9");
                            }
                            Inventory inventory = (Inventory) Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {Inventory.class},
                                    (self, call, parameters) -> switch (call.getName()) {
                                        case "equals" -> self == parameters[0];
                                        case "hashCode" -> System.identityHashCode(self);
                                        default -> "inventory";
                                    });
                            registered.add(inventory);
                            return inventory;
                        }
                        case "deleteInventory" -> registered.remove(arguments[0]);
                        case "deleteInventories" -> registered.clear();
                        case "openInventory" -> opened.add(((Player) arguments[0]).getName());
                        default -> throw new UnsupportedOperationException(method.getName());
                    }
                    return null;
                });
    }
}
