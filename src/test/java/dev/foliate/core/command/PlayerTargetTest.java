package dev.foliate.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.foliate.core.message.MessageService;
import dev.foliate.testing.CommandSource;
import dev.foliate.testing.TestCommands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.entity.ZombieMock;
import org.slf4j.helpers.NOPLogger;

/**
 * {@link PlayerTarget} on a subcommand of the test's own, {@code /foliate thing [player]}, with
 * messages of its own: it knows no feature.
 */
class PlayerTargetTest {

    private static final String PERMISSION = "test.thing";
    private static final String PERMISSION_OTHERS = "test.thing.others";
    private static final String MESSAGES = """
            prefix: ""
            test:
              name-required: "name required"
              unknown: "<player> unknown"
              others-denied: "others denied"
            """;

    @TempDir
    Path directory;

    private ServerMock server;
    private Plugin plugin;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    /** Each run of the action, as {@code sender -> target}. */
    private final List<String> runs = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        MessageService messages = new MessageService(
                directory,
                name -> new ByteArrayInputStream(MESSAGES.getBytes(StandardCharsets.UTF_8)),
                NOPLogger.NOP_LOGGER);
        messages.enable();
        PlayerTarget target = new PlayerTarget(server, messages, PERMISSION_OTHERS, "test");
        Subcommand thing = new Subcommand() {
            @Override
            public String name() {
                return "thing";
            }

            @Override
            public String permission() {
                return PERMISSION;
            }

            @Override
            public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
                target.addTo(node, (sender, player) -> runs.add(sender.getName() + " -> " + player.getName()));
            }
        };
        dispatcher = TestCommands.registerExecuteAs(TestCommands.dispatcher(thing), server);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void playerIsTheirOwnTarget() throws Exception {
        PlayerMock alex = player("Alex");

        dispatcher.execute("foliate thing", new CommandSource(alex));

        assertEquals(List.of("Alex -> Alex"), runs);
    }

    @Test
    void runningAsOneselfNeedsNothingMore() throws Exception {
        PlayerMock alex = player("Alex");

        dispatcher.execute("execute as Alex run foliate thing", new CommandSource(alex));

        assertEquals(List.of("Alex -> Alex"), runs);
    }

    @Test
    void runningAsAnotherPlayerNeedsOthers() throws Exception {
        PlayerMock alex = player("Alex");
        player("Bob");

        dispatcher.execute("execute as Bob run foliate thing", new CommandSource(alex));

        assertEquals(List.of(), runs);
        assertEquals("others denied", TestCommands.plain(alex.nextComponentMessage()));

        alex.addAttachment(plugin, PERMISSION_OTHERS, true);
        dispatcher.execute("execute as Bob run foliate thing", new CommandSource(alex));

        assertEquals(List.of("Alex -> Bob"), runs);
        assertNull(alex.nextComponentMessage());
    }

    @Test
    void namingAnotherPlayerNeedsOthers() throws Exception {
        PlayerMock alex = player("Alex");
        player("Bob");

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate thing Bob", new CommandSource(alex)));
        assertEquals(List.of(), runs);

        alex.addAttachment(plugin, PERMISSION_OTHERS, true);
        dispatcher.execute("foliate thing Bob", new CommandSource(alex));

        assertEquals(List.of("Alex -> Bob"), runs);
    }

    @Test
    void consoleMustNameAPlayer() throws Exception {
        ConsoleCommandSenderMock console = server.getConsoleSender();

        dispatcher.execute("foliate thing", new CommandSource(console));

        assertEquals(List.of(), runs);
        assertEquals("name required", TestCommands.plain(console.nextComponentMessage()));
    }

    @Test
    void consoleCanNameAPlayerOrRunAsOne() throws Exception {
        player("Bob");
        CommandSender console = server.getConsoleSender();

        dispatcher.execute("foliate thing Bob", new CommandSource(console));
        dispatcher.execute("execute as Bob run foliate thing", new CommandSource(console));

        assertEquals(List.of("CONSOLE -> Bob", "CONSOLE -> Bob"), runs);
    }

    /** Without an executor, whatever the sender's permissions, there is nobody to target. */
    @Test
    void sourceWithoutAnExecutorMustNameAPlayer() throws Exception {
        PlayerMock alex = player("Alex");

        dispatcher.execute("foliate thing", new CommandSource(alex, null));

        assertEquals(List.of(), runs);
        assertEquals("name required", TestCommands.plain(alex.nextComponentMessage()));
    }

    @Test
    void unknownOrOfflinePlayerIsReportedAsNotOnline() throws Exception {
        PlayerMock operator = operator();
        player("Gone").disconnect();

        dispatcher.execute("foliate thing Nobody", new CommandSource(operator));
        dispatcher.execute("foliate thing Gone", new CommandSource(operator));

        assertEquals(List.of(), runs);
        assertEquals("Nobody unknown", TestCommands.plain(operator.nextComponentMessage()));
        assertEquals("Gone unknown", TestCommands.plain(operator.nextComponentMessage()));
    }

    @Test
    void aNameIsMatchedWholeButWhateverItsCase() throws Exception {
        PlayerMock operator = operator();
        player("Alex");

        dispatcher.execute("foliate thing Ale", new CommandSource(operator));
        dispatcher.execute("foliate thing alex", new CommandSource(operator));

        assertEquals("Ale unknown", TestCommands.plain(operator.nextComponentMessage()));
        assertEquals(List.of(operator.getName() + " -> Alex"), runs);
    }

    /** Under execute as @e, the executor may be an entity that is not a player. */
    @Test
    void anExecutorThatIsNotAPlayerMustNameAPlayer() throws Exception {
        PlayerMock operator = operator();
        Entity zombie = new ZombieMock(server, UUID.randomUUID());

        dispatcher.execute("foliate thing", new CommandSource(operator, zombie));

        assertEquals(List.of(), runs);
        assertEquals("name required", TestCommands.plain(operator.nextComponentMessage()));
    }

    /** A player the sender cannot see, such as one who vanished, is reported as not online. */
    @Test
    void hiddenPlayerIsReportedAsNotOnline() throws Exception {
        PlayerMock operator = operator();
        PlayerMock hidden = player("Alice");
        operator.hidePlayer(plugin, hidden);

        dispatcher.execute("foliate thing Alice", new CommandSource(operator));

        assertEquals(List.of(), runs);
        assertEquals("Alice unknown", TestCommands.plain(operator.nextComponentMessage()));
    }

    @Test
    void suggestionsAreThePlayersTheSenderCanSee() throws Exception {
        PlayerMock operator = operator();
        player("Alex");
        PlayerMock hidden = player("Alice");
        player("Bob");
        operator.hidePlayer(plugin, hidden);

        assertEquals(List.of("Alex"), suggestions("foliate thing a", operator));
    }

    @Test
    void senderWithoutOthersIsSuggestedNobody() throws Exception {
        PlayerMock alex = player("Alex");
        player("Bob");

        assertEquals(List.of(), suggestions("foliate thing ", alex));
    }

    @Test
    void messageKeysAreUnderTheSection() {
        assertEquals(
                List.of("command.stats.name-required", "command.stats.unknown", "command.stats.others-denied"),
                PlayerTarget.messageKeys("command.stats"));
    }

    private List<String> suggestions(String typed, CommandSender sender) throws Exception {
        return dispatcher.getCompletionSuggestions(dispatcher.parse(typed, new CommandSource(sender))).get().getList().stream()
                .map(Suggestion::getText)
                .toList();
    }

    /** A player with the subcommand's own permission, and nothing more. */
    private PlayerMock player(String name) {
        PlayerMock player = server.addPlayer(name);
        player.addAttachment(plugin, PERMISSION, true);
        return player;
    }

    private PlayerMock operator() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        assertTrue(player.hasPermission(PERMISSION_OTHERS));
        return player;
    }
}
