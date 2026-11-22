package dev.foliate.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.foliate.testing.CommandSource;
import dev.foliate.testing.TestCommands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * How the command is assembled, with subcommands of the test's own: it knows no feature. Each
 * subcommand's own behaviour is tested next to it.
 */
class RootCommandTest {

    private ServerMock server;
    private Plugin plugin;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void eachSubcommandChecksItsOwnPermission() throws Exception {
        CommandDispatcher<CommandSourceStack> commands =
                TestCommands.dispatcher(stub("first", "test.first"), stub("second", "test.second"));
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.first", true);

        assertEquals(1, commands.execute("foliate first", new CommandSource(player)));
        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate second", new CommandSource(player)));
    }

    @Test
    void subcommandThatSetsItsOwnRequirementStillNeedsItsPermission() {
        Subcommand restricted = stub("secret", "test.secret", node -> node.requires(source -> true).executes(context -> 1));
        CommandDispatcher<CommandSourceStack> commands = TestCommands.dispatcher(restricted);
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.other", true);

        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate secret", new CommandSource(player)));
    }

    @Test
    void commandIsHiddenWhenItsOnlySubcommandIsUnavailable() {
        Subcommand playersOnly = availableOnlyTo("menu", "test.menu", source -> source.getSender() instanceof Player);
        CommandDispatcher<CommandSourceStack> commands = TestCommands.dispatcher(playersOnly);

        assertThrows(
                CommandSyntaxException.class,
                () -> commands.execute("foliate menu", new CommandSource(server.getConsoleSender())));
    }

    @Test
    void playerWithoutAnyPermissionCannotSeeTheCommand() {
        CommandDispatcher<CommandSourceStack> commands = TestCommands.dispatcher(stub("first", "test.first"));
        PlayerMock player = server.addPlayer();

        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate first", new CommandSource(player)));
        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate", new CommandSource(player)));
    }

    @Test
    void mistakesInTheListOfSubcommandsAreCaughtAtStartup() {
        Subcommand first = stub("same", "test.first");
        Subcommand second = stub("same", "test.second");
        Subcommand idle = stub("idle", "test.first", node -> {});

        assertThrows(IllegalArgumentException.class, () -> new RootCommand("foliate", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RootCommand("foliate", List.of(first, second)));
        assertThrows(IllegalStateException.class, new RootCommand("foliate", List.of(idle))::build);
    }

    @Test
    void theBareCommandRunsWhenTheCommandIsTypedAlone() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Subcommand fly = stub("ignored", "test.fly", node -> node.executes(context -> runs.incrementAndGet()));
        CommandDispatcher<CommandSourceStack> commands = dispatcher(fly, stub("first", "test.first"));
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.fly", true);
        player.addAttachment(plugin, "test.first", true);

        assertEquals(1, commands.execute("foliate", new CommandSource(player)));
        assertEquals(1, runs.get());
        assertEquals(1, commands.execute("foliate first", new CommandSource(player)));
        // Its name is not a subcommand.
        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate ignored", new CommandSource(player)));
    }

    /** The root lets in whoever may use a subcommand: typed alone, the command checks the bare command's permission itself. */
    @Test
    void theBareCommandChecksItsOwnPermission() throws Exception {
        AtomicInteger runs = new AtomicInteger();
        Subcommand fly = stub("fly", "test.fly", node -> node.executes(context -> runs.incrementAndGet()));
        CommandDispatcher<CommandSourceStack> commands = dispatcher(fly, stub("first", "test.first"));
        PlayerMock subcommandOnly = server.addPlayer();
        subcommandOnly.addAttachment(plugin, "test.first", true);
        PlayerMock nothing = server.addPlayer();

        // The game's own failure, not a result of 0: execute store success and command blocks see it fail.
        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate", new CommandSource(subcommandOnly)));
        assertEquals(1, commands.execute("foliate first", new CommandSource(subcommandOnly)));
        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate", new CommandSource(nothing)));
        assertEquals(0, runs.get());
    }

    /** As for a subcommand: what the bare command requires in its build, and its availability, hold too. */
    @Test
    void aPlayerAllowedOnlyTheBareCommandRunsItAndNoSubcommand() throws Exception {
        CommandDispatcher<CommandSourceStack> commands = dispatcher(stub("fly", "test.fly"), stub("first", "test.first"));
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.fly", true);

        assertEquals(1, commands.execute("foliate", new CommandSource(player)));
        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate first", new CommandSource(player)));
    }

    /** Let in by a subcommand's permission, the console is still refused a bare command for players only. */
    @Test
    void theBareCommandsAvailabilityIsCheckedWhenItRuns() {
        Subcommand playersOnly = stub("fly", "test.fly", node -> node.executes(context -> 1),
                source -> source.getSender() instanceof Player);
        CommandDispatcher<CommandSourceStack> commands = dispatcher(playersOnly, stub("first", "test.first"));

        assertThrows(
                CommandSyntaxException.class,
                () -> commands.execute("foliate", new CommandSource(server.getConsoleSender())));
    }

    @Test
    void theBareCommandKeepsItsOwnRequirementAndAvailability() {
        Subcommand required = stub("fly", "test.fly", node -> node.requires(source -> false).executes(context -> 1));
        Subcommand playersOnly = stub("fly", "test.fly", node -> node.executes(context -> 1),
                source -> source.getSender() instanceof Player);
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.fly", true);

        assertThrows(CommandSyntaxException.class, () -> dispatcher(required).execute("foliate", new CommandSource(player)));
        assertThrows(
                CommandSyntaxException.class,
                () -> dispatcher(playersOnly).execute("foliate", new CommandSource(server.getConsoleSender())));
    }

    @Test
    void mistakesInTheBareCommandAreCaughtAtStartup() {
        Subcommand idle = stub("fly", "test.fly", node -> {});
        Subcommand withArgument = stub("fly", "test.fly", node -> node.executes(context -> 1)
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("on").executes(context -> 1)));
        Subcommand redirected = stub("fly", "test.fly", node -> node.executes(context -> 1)
                .redirect(LiteralArgumentBuilder.<CommandSourceStack>literal("elsewhere").build()));
        Subcommand fine = stub("fly", "test.fly");

        assertThrows(IllegalArgumentException.class, () -> new RootCommand("foliate", null, List.of()));
        assertThrows(IllegalStateException.class, new RootCommand("foliate", idle, List.of())::build);
        assertThrows(IllegalStateException.class, new RootCommand("foliate", withArgument, List.of())::build);
        assertThrows(IllegalStateException.class, new RootCommand("foliate", redirected, List.of())::build);
        // A command that only runs alone needs no subcommand.
        new RootCommand("foliate", fine, List.of()).build();
    }

    @Test
    void atMostOneModuleGivesTheBareCommand() {
        Subcommand fly = stub("fly", "test.fly");
        Subcommand walk = stub("walk", "test.walk");

        assertNull(SubcommandProvider.bareCommandOf(List.of(provider(null), provider(null))));
        assertSame(fly, SubcommandProvider.bareCommandOf(List.of(provider(null), provider(fly))));
        assertThrows(IllegalStateException.class, () -> SubcommandProvider.bareCommandOf(List.of(provider(fly), provider(walk))));
    }

    /** {@code /foliate add <player> <minutes>}, with suggestions, as a subcommand with two arguments builds it. */
    private static Subcommand add() {
        return stub("add", "test.add", node -> node.then(Commands.argument("player", StringArgumentType.word())
                .suggests((context, suggestions) -> suggestions.suggest("Steve").buildFuture())
                .then(Commands.argument("minutes", IntegerArgumentType.integer(1)).executes(context -> 7))));
    }

    @Test
    void aSubcommandTypedWithoutItsArgumentsShowsWhatFollows() throws Exception {
        List<String> shown = new ArrayList<>();
        CommandDispatcher<CommandSourceStack> commands = withUsage(shown, add());
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.add", true);

        commands.execute("foliate add", new CommandSource(player));
        commands.execute("foliate add Steve", new CommandSource(player));

        assertEquals(List.of("/foliate add <player> <minutes>", "/foliate add <player> <minutes>"), shown);
    }

    /** The copy made to show usages keeps what each node does, requires and suggests. */
    @Test
    void aCompleteCommandRunsAsBeforeWithItsSuggestions() throws Exception {
        List<String> shown = new ArrayList<>();
        CommandDispatcher<CommandSourceStack> commands = withUsage(shown, add());
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.add", true);
        PlayerMock other = server.addPlayer();

        assertEquals(7, commands.execute("foliate add Steve 5", new CommandSource(player)));
        assertEquals(List.of(), shown);
        assertEquals(
                List.of("Steve"),
                commands.getCompletionSuggestions(commands.parse("foliate add ", new CommandSource(player))).get().getList()
                        .stream().map(suggestion -> suggestion.getText()).toList());
        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate add", new CommandSource(other)));
    }

    /** Optional parts read as such, and a choice shows only what the sender may use. */
    @Test
    void theUsageShowsOptionalPartsAndOnlyWhatTheSenderMayUse() throws Exception {
        List<String> shown = new ArrayList<>();
        Subcommand speed = stub("speed", "test.speed", node -> node
                .then(Commands.argument("speed", IntegerArgumentType.integer(1, 10)).executes(context -> 1)
                        .then(Commands.argument("player", StringArgumentType.word()).executes(context -> 1)))
                .then(Commands.literal("reset").requires(source -> source.getSender().hasPermission("test.reset"))
                        .executes(context -> 1)));
        CommandDispatcher<CommandSourceStack> commands = withUsage(shown, speed);
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.speed", true);

        commands.execute("foliate speed", new CommandSource(player));
        assertEquals(List.of("/foliate speed <speed> [<player>]"), shown);

        shown.clear();
        player.addAttachment(plugin, "test.reset", true);
        commands.execute("foliate speed", new CommandSource(player));
        assertEquals(List.of("/foliate speed <speed> [<player>]", "/foliate speed reset"), shown);
    }

    /** The copy keeps what an inner node requires: shown or not, it still refuses whoever may not run it. */
    @Test
    void anInnerRequirementStillRefusesAfterTheCopy() {
        Subcommand speed = stub("speed", "test.speed", node -> node
                .then(Commands.literal("reset").requires(source -> source.getSender().hasPermission("test.reset"))
                        .executes(context -> 1)));
        CommandDispatcher<CommandSourceStack> commands = withUsage(new ArrayList<>(), speed);
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.speed", true);

        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate speed reset", new CommandSource(player)));
    }

    /** With nothing allowed after it, the node is incomplete as before, with the game's error and where it stops. */
    @Test
    void anIncompleteNodeWithNothingAllowedAfterItIsTheGamesError() {
        List<String> shown = new ArrayList<>();
        Subcommand speed = stub("speed", "test.speed", node -> node
                .then(Commands.literal("reset").requires(source -> false).executes(context -> 1)));
        CommandDispatcher<CommandSourceStack> commands = withUsage(shown, speed);
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.speed", true);

        CommandSyntaxException error =
                assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate speed", new CommandSource(player)));

        assertEquals("foliate speed", error.getInput());
        assertEquals(List.of(), shown);
    }

    /** A redirect to the command, as /execute run makes, reaches the copied nodes. */
    @Test
    void aRedirectToTheCommandReachesTheCopiedNodes() throws Exception {
        CommandDispatcher<CommandSourceStack> commands = withUsage(new ArrayList<>(), add());
        commands.register(Commands.literal("again").redirect(commands.getRoot().getChild("foliate")));
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.add", true);

        assertEquals(7, commands.execute("again add Steve 5", new CommandSource(player)));
    }

    /** Without a way to show it, an incomplete command stays the game's error. */
    @Test
    void withoutUsageAnIncompleteCommandIsTheGamesError() {
        CommandDispatcher<CommandSourceStack> commands = TestCommands.dispatcher(add());
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, "test.add", true);

        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate add", new CommandSource(player)));
    }

    private static CommandDispatcher<CommandSourceStack> withUsage(List<String> shown, Subcommand... subcommands) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(
                new RootCommand("foliate", null, List.of(subcommands), (sender, lines) -> shown.addAll(lines)).build());
        return dispatcher;
    }

    private static CommandDispatcher<CommandSourceStack> dispatcher(Subcommand bare, Subcommand... subcommands) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new RootCommand("foliate", bare, List.of(subcommands)).build());
        return dispatcher;
    }

    private static SubcommandProvider provider(@Nullable Subcommand bare) {
        return new SubcommandProvider() {
            @Override
            public List<Subcommand> subcommands() {
                return List.of();
            }

            @Override
            public @Nullable Subcommand bareCommand() {
                return bare;
            }
        };
    }

    private static Subcommand stub(String name, String permission) {
        return stub(name, permission, node -> node.executes(context -> 1));
    }

    private static Subcommand availableOnlyTo(String name, String permission, Predicate<CommandSourceStack> availability) {
        return stub(name, permission, node -> node.executes(context -> 1), availability);
    }

    private static Subcommand stub(
            String name, String permission, Consumer<LiteralArgumentBuilder<CommandSourceStack>> build) {
        return stub(name, permission, build, source -> true);
    }

    private static Subcommand stub(
            String name,
            String permission,
            Consumer<LiteralArgumentBuilder<CommandSourceStack>> build,
            Predicate<CommandSourceStack> availability) {
        return new Subcommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public String permission() {
                return permission;
            }

            @Override
            public boolean isAvailableTo(CommandSourceStack source) {
                return availability.test(source);
            }

            @Override
            public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
                build.accept(node);
            }
        };
    }
}
