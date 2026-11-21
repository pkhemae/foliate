package dev.foliate.modules.scoreboard;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigException;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.scheduler.ManualSchedulers;
import dev.foliate.testing.RecordingLogger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.IllegalPluginAccessException;
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
class ScoreboardModuleTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private Plugin plugin;
    private ConfigService<PluginConfig> config;
    private MessageService messages;
    private ScoreboardModule scoreboard;
    private final ManualSchedulers schedulers = new ManualSchedulers();
    private final List<FakeBoard> boards = new ArrayList<>();
    private final RecordingLogger logger = new RecordingLogger();
    /**
     * Makes the {@code <flaky>} tag fail as an expansion built against another version of its
     * plugin does. MiniMessage renders a tag whose resolver throws an exception as plain text, but
     * lets an error through.
     */
    private boolean expansionBroken;
    /** The name of the one player for whom the {@code <flaky>} tag fails, when set. */
    private String brokenFor = "";
    /** Run once, the next time the module creates a board. */
    private Runnable onCreate = () -> {};

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        messages = new MessageService(directory, getClass().getClassLoader()::getResourceAsStream, NOPLogger.NOP_LOGGER);
        messages.enable();
        messages.addSharedResolver(TagResolver.resolver("flaky", (arguments, context) -> {
            if (expansionBroken || (context.target() instanceof Player viewer && viewer.getName().equals(brokenFor))) {
                throw new NoClassDefFoundError("a class the expansion was built against");
            }
            return Tag.preProcessParsed("fine");
        }));
        scoreboard = newModule(() -> {});
        scoreboard.enable();
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

    /** Joining runs on the player's own thread: no need to wait for the first refresh. */
    @Test
    void boardIsDrawnAsSoonAsThePlayerJoinsThenAtEachRefresh() {
        server.addPlayer("Alex");

        FakeBoard board = boards.getLast();
        assertEquals("Foliate", board.title);
        assertEquals(1, board.draws);

        schedulers.tick(19);
        assertEquals(1, board.draws);
        schedulers.tick(1);
        assertEquals(2, board.draws);
    }

    @Test
    void aTemplateThatFailsIsLoggedOnceAndLeavesTheBoardsAsTheyWere() throws Exception {
        configure("scoreboard:\n  title: \"<flaky>\"\n");
        server.addPlayer("Alex");
        server.addPlayer("Sam");
        expansionBroken = true;

        schedulers.tick(100);

        assertEquals(1, errors().size(), errors().toString());
        assertTrue(boards.stream().allMatch(board -> board.title.equals("fine")), boards.toString());
    }

    @Test
    void aTemplateFailingAgainAfterDrawingIsLoggedAgain() throws Exception {
        configure("scoreboard:\n  title: \"<flaky>\"\n");
        server.addPlayer("Alex");
        expansionBroken = true;
        schedulers.tick(20);

        expansionBroken = false;
        schedulers.tick(20);
        expansionBroken = true;
        schedulers.tick(60);

        assertEquals(2, errors().size(), errors().toString());
    }

    @Test
    void aTemplateStillFailingAfterAReloadIsLoggedAgain() throws Exception {
        configure("scoreboard:\n  title: \"<flaky>\"\n");
        server.addPlayer("Alex");
        expansionBroken = true;
        schedulers.tick(20);

        scoreboard.reload();
        schedulers.tick(60);

        assertEquals(2, errors().size(), errors().toString());
    }

    /** The board that failed is gone: another one drawing is enough to log the next failure. */
    @Test
    void aFailureAfterAnotherBoardDrewIsLoggedAgain() throws Exception {
        configure("scoreboard:\n  title: \"<flaky>\"\n");
        expansionBroken = true;
        PlayerMock alex = server.addPlayer("Alex");
        assertEquals(1, errors().size(), errors().toString());
        alex.disconnect();

        expansionBroken = false;
        server.addPlayer("Sam");
        schedulers.tick(100);
        expansionBroken = true;
        schedulers.tick(20);

        assertEquals(2, errors().size(), errors().toString());
    }

    /** A board failing for one player only must not log on every refresh while others draw. */
    @Test
    void aBoardThatKeepsFailingWhileOthersDrawIsLoggedOnce() throws Exception {
        configure("scoreboard:\n  title: \"<flaky>\"\n");
        server.addPlayer("Sam");
        brokenFor = "Alex";
        server.addPlayer("Alex");

        schedulers.tick(100);

        assertEquals(1, errors().size(), errors().toString());
    }

    @Test
    void checkReportsAPlayerWithoutABoard() {
        PlayerMock player = server.addPlayer("Alex");
        assertEquals(null, new ScoreboardCheck(scoreboard).problemWith(player, 1));

        server.getPluginManager().callEvent(new PlayerQuitEvent(player, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));

        assertEquals("no scoreboard was created for Alex", new ScoreboardCheck(scoreboard).problemWith(player, 1));
        assertEquals(null, new ScoreboardCheck(scoreboard).problemAfterQuit(player.getUniqueId(), "Alex"));
    }

    @Test
    void checkAfterTheQuitReportsABoardThatWasKept() {
        PlayerMock player = server.addPlayer("Alex");

        assertEquals(
                "the scoreboard of Alex was kept after the player left",
                new ScoreboardCheck(scoreboard).problemAfterQuit(player.getUniqueId(), "Alex"));
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
    void noBoardIsCreatedWhenTheScoreboardIsDisabled() throws Exception {
        configure("scoreboard:\n  enabled: false\n");

        server.addPlayer();
        schedulers.tick(1);

        assertTrue(boards.isEmpty());
    }

    @Test
    void reloadAppliesANewConfigurationToOnlinePlayers() throws Exception {
        server.addPlayer("Alex");
        schedulers.tick(1);
        configure("scoreboard:\n  title: \"<red>Changed\"\n");

        scoreboard.reload();
        // A reload runs off the tick threads: the redraw is deferred to the player's scheduler.
        schedulers.tick(1);

        // Redrawn, not rebuilt: a new board would flicker.
        assertEquals(1, boards.size());
        assertFalse(boards.getFirst().deleted);
        assertEquals("Changed", boards.getFirst().title);
    }

    /** The interval belongs to the board's timer: changing it needs a new one. */
    @Test
    void reloadChangingTheRefreshIntervalRebuildsTheBoard() throws Exception {
        server.addPlayer("Alex");
        schedulers.tick(1);
        configure("scoreboard:\n  refresh-ticks: 5\n");

        scoreboard.reload();
        schedulers.tick(2);

        assertEquals(2, boards.size());
        assertTrue(boards.getFirst().deleted);
        assertFalse(boards.getLast().deleted);
    }

    @Test
    void reloadTurningTheScoreboardOffRemovesTheBoards() throws Exception {
        server.addPlayer();
        schedulers.tick(1);
        configure("scoreboard:\n  enabled: false\n");

        scoreboard.reload();
        schedulers.tick(2);

        assertEquals(1, boards.size());
        assertTrue(boards.getFirst().deleted);
        assertEquals(0, schedulers.pendingTasks());
    }

    @Test
    void reloadTurningTheScoreboardOnGivesTheOnlinePlayersABoard() throws Exception {
        configure("scoreboard:\n  enabled: false\n");
        scoreboard.reload();
        server.addPlayer("Alex");
        schedulers.tick(1);
        assertEquals(0, boards.size());

        configure("scoreboard:\n  enabled: true\n");
        scoreboard.reload();
        schedulers.tick(2);

        assertEquals(1, boards.size());
        assertFalse(boards.getFirst().deleted);
        assertEquals("Foliate", boards.getFirst().title);
    }

    @Test
    void reloadCarriesOnPastAPlayerWhoIsDisconnecting() throws Exception {
        server.addPlayer("First");
        PlayerMock leaving = server.addPlayer("Leaving");
        server.addPlayer("Third");
        schedulers.tick(1);
        configure("scoreboard:\n  title: \"<red>Changed\"\n");

        // Still listed as online, but its scheduler already refuses work.
        schedulers.retire(leaving);
        scoreboard.reload();
        schedulers.tick(2);

        List<String> titles = boards.stream().filter(board -> !board.deleted).map(board -> board.title).toList();
        assertEquals(2, titles.stream().filter("Changed"::equals).count(), titles.toString());
    }

    @Test
    void boardKeepsRefreshingAtTheConfiguredInterval() throws Exception {
        configure("scoreboard:\n  refresh-ticks: 5\n");
        server.addPlayer("Alex");
        schedulers.tick(1);
        int drawsAfterFirstTick = boards.getLast().draws;

        schedulers.tick(40);

        assertEquals(drawsAfterFirstTick + 8, boards.getLast().draws);
    }

    @Test
    void playersAlreadyOnlineGetABoardWhenTheModuleStarts() throws Exception {
        scoreboard.disable();
        boards.clear();
        server.addPlayer("Alex");
        ScoreboardModule late = newModule(() -> {});

        late.enable();
        schedulers.tick(2);

        assertEquals(1, boards.size());
        assertEquals("Foliate", boards.getFirst().title);
    }

    @Test
    void moduleDoesNotStartOnAServerTheBoardLibraryDoesNotSupport() {
        ScoreboardModule unsupported = newModule(() -> {
            throw new ScoreboardUnavailableException("unsupported");
        });

        assertThrows(ScoreboardUnavailableException.class, unsupported::enable);
    }

    @Test
    void legacyColourCodesInTheConfigurationAreRejectedAtLoad() {
        assertThrows(ConfigException.class, () -> configure("scoreboard:\n  title: \"\u00A7cLegacy\"\n"));
        assertThrows(ConfigException.class, () -> configure("scoreboard:\n  lines: [\"ok\", \"\u00A7cLegacy\"]\n"));
    }

    @Test
    void outOfRangeScoreboardSettingsAreRejectedAtLoad() {
        assertThrows(ConfigException.class, () -> configure("scoreboard:\n  refresh-ticks: 0\n"));
        String sixteenLines = "scoreboard:\n  lines:\n" + "  - line\n".repeat(16);
        assertThrows(ConfigException.class, () -> configure(sixteenLines));
    }

    /** A board whose refresh cannot be scheduled would never be drawn, nor removed by a quit. */
    @Test
    void noBoardIsKeptForAPlayerWhoseSchedulerIsRetired() {
        PlayerMock leaving = server.addPlayer("Leaving");
        scoreboard.onQuit(new PlayerQuitEvent(leaving, Component.empty(), PlayerQuitEvent.QuitReason.DISCONNECTED));
        schedulers.retire(leaving);

        scoreboard.onJoin(new PlayerJoinEvent(leaving, Component.empty()));

        assertFalse(scoreboard.hasBoard(leaving.getUniqueId()));
        assertTrue(boards.stream().allMatch(board -> board.deleted));
    }

    @Test
    void disablingTheModuleRemovesEveryBoard() {
        server.addPlayer();
        server.addPlayer();
        schedulers.tick(1);

        scoreboard.disable();

        assertEquals(2, boards.size());
        assertFalse(boards.stream().anyMatch(board -> !board.deleted));
        assertEquals(0, schedulers.pendingTasks());
    }

    /**
     * When the plugin is disabled while the server runs, work already under way on a player's
     * thread can outlive the module: here, the redraw a reload queued just before.
     */
    @Test
    void workStillUnderWayWhenTheModuleIsDisabledLeavesNoBoard() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        configure("scoreboard:\n  refresh-ticks: 5\n");
        scoreboard.reload();

        scoreboard.disable();
        schedulers.tick(1);
        scoreboard.onJoin(new PlayerJoinEvent(player, Component.empty()));

        assertFalse(scoreboard.hasBoard(player.getUniqueId()));
        assertTrue(boards.stream().allMatch(board -> board.deleted), boards.toString());
        assertEquals(0, schedulers.pendingTasks());
    }

    /**
     * disable() runs on another thread while a player's thread is creating a board, past the
     * check that the module runs: the board must not outlive the module.
     */
    @Test
    void boardCreatedWhileTheModuleIsDisabledIsRemoved() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        onCreate = () -> scoreboard.disable();
        configure("scoreboard:\n  refresh-ticks: 5\n");
        scoreboard.reload();
        schedulers.tick(1);

        assertFalse(scoreboard.hasBoard(player.getUniqueId()));
        assertTrue(boards.stream().allMatch(board -> board.deleted), boards.toString());
        assertEquals(0, schedulers.pendingTasks());
    }

    /** The plugin disabled between the module's check and the timer: the server refuses it. */
    @Test
    void boardWhoseTimerIsRefusedByADisabledPluginIsRemovedQuietly() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        configure("scoreboard:\n  refresh-ticks: 5\n");
        scoreboard.reload();
        server.getPluginManager().disablePlugin(plugin);
        schedulers.refuseTimers();

        schedulers.tick(1);

        assertFalse(scoreboard.hasBoard(player.getUniqueId()));
        assertTrue(boards.stream().allMatch(board -> board.deleted), boards.toString());
    }

    /** Refused for another reason, the failure is not hidden, and the board is not left behind. */
    @Test
    void boardWhoseTimerIsRefusedOtherwiseIsRemovedAndTheFailureReported() throws Exception {
        server.addPlayer("Alex");
        configure("scoreboard:\n  refresh-ticks: 5\n");
        scoreboard.reload();
        schedulers.refuseTimers();

        assertThrows(IllegalPluginAccessException.class, () -> schedulers.tick(1));
        assertTrue(boards.stream().allMatch(board -> board.deleted), boards.toString());
    }

    private void configure(String yaml) throws ConfigException {
        try {
            Files.writeString(directory.resolve("config.yml"), yaml);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        config.reload();
    }

    private ScoreboardModule newModule(ScoreboardModule.SupportCheck supportCheck) {
        return new ScoreboardModule(plugin, schedulers, () -> config.get().scoreboard(), messages, player -> {
            FakeBoard board = new FakeBoard();
            boards.add(board);
            Runnable hook = onCreate;
            onCreate = () -> {};
            hook.run();
            return board;
        }, supportCheck, logger);
    }

    private List<String> errors() {
        return logger.lines().stream().filter(line -> line.startsWith("ERROR")).toList();
    }

    private static final class FakeBoard implements ScoreboardDisplay {

        private String title = "";
        private List<String> lines = List.of();
        private boolean deleted;
        private int draws;

        @Override
        public void update(Component title, List<Component> lines) {
            if (deleted) {
                return;
            }
            draws++;
            this.title = PlainTextComponentSerializer.plainText().serialize(title);
            this.lines = lines.stream().map(PlainTextComponentSerializer.plainText()::serialize).toList();
        }

        @Override
        public void delete() {
            deleted = true;
        }

        @Override
        public String toString() {
            return title + (deleted ? " (deleted)" : "");
        }
    }
}
