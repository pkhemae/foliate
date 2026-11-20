package dev.foliate.hooks.zmenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.api.menu.MenuException;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.scheduler.FoliaSchedulers;
import fr.maxlego08.menu.api.Inventory;
import fr.maxlego08.menu.api.InventoryManager;
import fr.maxlego08.menu.api.exceptions.InventoryException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
    private ZMenuHook hook;
    private boolean zMenuRejectsFiles;
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
        hook = new ZMenuHook(plugin, new FoliaSchedulers(plugin), messages, this::fakeZMenu);
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

    private static String plain(PlayerMock player) {
        return PlainTextComponentSerializer.plainText().serialize(player.nextComponentMessage());
    }

    /** Implements only the handful of zMenu methods the hook calls. */
    private InventoryManager fakeZMenu() {
        return (InventoryManager) Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {InventoryManager.class}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "loadInventoryOrSaveResource" -> {
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
