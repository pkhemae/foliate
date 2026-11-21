package dev.foliate.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.foliate.testing.CommandSource;
import dev.foliate.testing.TestCommands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
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
