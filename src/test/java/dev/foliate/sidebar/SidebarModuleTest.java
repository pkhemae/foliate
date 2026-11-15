package dev.foliate.sidebar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.scheduler.ManualSchedulers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
 * Covers the module's bookkeeping with fake boards. Whether FastBoard itself draws on a Folia
 * server can only be checked with a connected client.
 */
class SidebarModuleTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private ConfigService config;
    private SidebarModule sidebar;
    private final ManualSchedulers schedulers = new ManualSchedulers();
    private final List<FakeBoard> boards = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        Plugin plugin = MockBukkit.createMockPlugin();
        config = new ConfigService(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, config, NOPLogger.NOP_LOGGER);
        messages.enable();
        sidebar = new SidebarModule(plugin, schedulers, config, messages, player -> {
            FakeBoard board = new FakeBoard();
            boards.add(board);
            return board;
        }, () -> {});
        sidebar.enable();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void joiningPlayerGetsABoardDrawnFromTheConfiguration() {
        PlayerMock player = server.addPlayer("Alex");

        schedulers.tick(1);

        FakeBoard board = boards.getLast();
        assertEquals("Foliate", board.title);
        assertTrue(board.lines.contains("Player: Alex"), board.lines.toString());
        assertTrue(board.lines.contains("Online: 1"), board.lines.toString());
        assertTrue(player.isOnline());
    }

    @Test
    void boardIsRemovedWhenThePlayerQuits() {
        PlayerMock player = server.addPlayer();
        schedulers.tick(1);

        player.disconnect();

        assertTrue(boards.getLast().deleted);
        assertEquals(0, schedulers.pendingTasks());
    }

    @Test
    void noBoardIsCreatedWhenTheSidebarIsDisabled() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "sidebar:\n  enabled: false\n");
        config.reload();

        server.addPlayer();
        schedulers.tick(1);

        assertTrue(boards.isEmpty());
    }

    @Test
    void reloadAppliesANewConfigurationToOnlinePlayers() throws Exception {
        server.addPlayer("Alex");
        schedulers.tick(1);
        Files.writeString(directory.resolve("config.yml"), "sidebar:\n  title: \"<red>Changed\"\n");
        config.reload();

        sidebar.reload();
        schedulers.tick(1);

        assertTrue(boards.getFirst().deleted);
        assertEquals("Changed", boards.getLast().title);
    }

    @Test
    void boardKeepsRefreshingAtTheConfiguredInterval() {
        server.addPlayer("Alex");
        schedulers.tick(1);
        int drawsAfterFirstTick = boards.getLast().draws;

        schedulers.tick(40);

        assertEquals(drawsAfterFirstTick + 2, boards.getLast().draws);
    }

    @Test
    void disablingTheModuleRemovesEveryBoard() {
        server.addPlayer();
        server.addPlayer();
        schedulers.tick(1);

        sidebar.disable();

        assertEquals(2, boards.size());
        assertFalse(boards.stream().anyMatch(board -> !board.deleted));
        assertEquals(0, schedulers.pendingTasks());
    }

    private static final class FakeBoard implements SidebarBoard {

        private String title = "";
        private List<String> lines = List.of();
        private boolean deleted;
        private int draws;

        @Override
        public void update(Component title, List<Component> lines) {
            draws++;
            this.title = PlainTextComponentSerializer.plainText().serialize(title);
            this.lines = lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        }

        @Override
        public void delete() {
            deleted = true;
        }
    }
}
