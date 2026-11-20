package dev.foliate.modules.sidebar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigException;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.scheduler.ManualSchedulers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.event.player.PlayerQuitEvent;
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
    private Plugin plugin;
    private ConfigService<PluginConfig> config;
    private MessageService messages;
    private SidebarModule sidebar;
    private final ManualSchedulers schedulers = new ManualSchedulers();
    private final List<FakeBoard> boards = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);
        messages.enable();
        sidebar = newModule(() -> {});
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
    void checkReportsAPlayerWithoutABoard() {
        PlayerMock player = server.addPlayer("Alex");
        assertEquals(null, sidebar.problemWith(player));

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertEquals("no sidebar was created for Alex", sidebar.problemWith(player));
        assertEquals(null, sidebar.problemAfterQuit(player.getUniqueId(), "Alex"));
    }

    @Test
    void checkAfterTheQuitReportsABoardThatWasKept() {
        PlayerMock player = server.addPlayer("Alex");

        assertEquals(
                "the sidebar of Alex was kept after the player left",
                sidebar.problemAfterQuit(player.getUniqueId(), "Alex"));
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
        configure("sidebar:\n  enabled: false\n");

        server.addPlayer();
        schedulers.tick(1);

        assertTrue(boards.isEmpty());
    }

    @Test
    void reloadAppliesANewConfigurationToOnlinePlayers() throws Exception {
        server.addPlayer("Alex");
        schedulers.tick(1);
        configure("sidebar:\n  title: \"<red>Changed\"\n");

        sidebar.reload();
        // A reload runs off the tick threads: the swap is deferred to the player's scheduler, and
        // the first draw comes a tick after that.
        schedulers.tick(2);

        assertTrue(boards.getFirst().deleted);
        assertEquals("Changed", boards.getLast().title);
    }

    @Test
    void reloadTurningTheSidebarOffRemovesTheBoards() throws Exception {
        server.addPlayer();
        schedulers.tick(1);
        configure("sidebar:\n  enabled: false\n");

        sidebar.reload();
        schedulers.tick(2);

        assertEquals(1, boards.size());
        assertTrue(boards.getFirst().deleted);
        assertEquals(0, schedulers.pendingTasks());
    }

    @Test
    void reloadCarriesOnPastAPlayerWhoIsDisconnecting() throws Exception {
        server.addPlayer("First");
        PlayerMock leaving = server.addPlayer("Leaving");
        server.addPlayer("Third");
        schedulers.tick(1);
        configure("sidebar:\n  title: \"<red>Changed\"\n");

        // Still listed as online, but its scheduler already refuses work.
        schedulers.retire(leaving);
        sidebar.reload();
        schedulers.tick(2);

        List<String> titles = boards.stream().filter(board -> !board.deleted).map(board -> board.title).toList();
        assertEquals(2, titles.stream().filter("Changed"::equals).count(), titles.toString());
    }

    @Test
    void boardKeepsRefreshingAtTheConfiguredInterval() throws Exception {
        configure("sidebar:\n  refresh-ticks: 5\n");
        server.addPlayer("Alex");
        schedulers.tick(1);
        int drawsAfterFirstTick = boards.getLast().draws;

        schedulers.tick(40);

        assertEquals(drawsAfterFirstTick + 8, boards.getLast().draws);
    }

    @Test
    void playersAlreadyOnlineGetABoardWhenTheModuleStarts() throws Exception {
        sidebar.disable();
        boards.clear();
        server.addPlayer("Alex");
        SidebarModule late = newModule(() -> {});

        late.enable();
        schedulers.tick(2);

        assertEquals(1, boards.size());
        assertEquals("Foliate", boards.getFirst().title);
    }

    @Test
    void moduleDoesNotStartOnAServerTheBoardLibraryDoesNotSupport() {
        SidebarModule unsupported = newModule(() -> {
            throw new SidebarUnavailableException("unsupported", null);
        });

        assertThrows(SidebarUnavailableException.class, unsupported::enable);
    }

    @Test
    void legacyColourCodesInTheConfigurationAreRejectedAtLoad() {
        assertThrows(ConfigException.class, () -> configure("sidebar:\n  title: \"\u00A7cLegacy\"\n"));
        assertThrows(ConfigException.class, () -> configure("sidebar:\n  lines: [\"ok\", \"\u00A7cLegacy\"]\n"));
    }

    @Test
    void outOfRangeSidebarSettingsAreRejectedAtLoad() {
        assertThrows(ConfigException.class, () -> configure("sidebar:\n  refresh-ticks: 0\n"));
        String sixteenLines = "sidebar:\n  lines:\n" + "  - line\n".repeat(16);
        assertThrows(ConfigException.class, () -> configure(sixteenLines));
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

    private void configure(String yaml) throws ConfigException {
        try {
            Files.writeString(directory.resolve("config.yml"), yaml);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        config.reload();
    }

    private SidebarModule newModule(SidebarModule.SupportCheck supportCheck) {
        return new SidebarModule(plugin, schedulers, () -> config.get().sidebar(), messages, player -> {
            FakeBoard board = new FakeBoard();
            boards.add(board);
            return board;
        }, supportCheck);
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
