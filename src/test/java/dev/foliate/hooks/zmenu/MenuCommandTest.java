package dev.foliate.hooks.zmenu;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.foliate.testing.CommandSource;
import dev.foliate.testing.PluginYml;
import dev.foliate.testing.TestCommands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@code /foliate menu}, with a menu plugin of the test's own that records what it opens. */
class MenuCommandTest {

    private ServerMock server;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private final List<String> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        PluginYml.registerPermissions(server);
        dispatcher = TestCommands.foliate(new MenuCommand((player, menu) -> opened.add(player.getName() + " " + menu)));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void menuOpensForThePlayerRunningTheCommand() throws Exception {
        // No operator status needed: the menu permission is granted to everyone by default.
        PlayerMock player = server.addPlayer("Alex");

        dispatcher.execute("foliate menu", new CommandSource(player));

        assertEquals(List.of("Alex example"), opened);
    }

    @Test
    void menuOpensForTheExecutorWhenItDiffersFromTheSender() throws Exception {
        PlayerMock target = server.addPlayer("Target");

        // What "/execute as Target run foliate menu" typed in the console looks like.
        dispatcher.execute("foliate menu", new CommandSource(server.getConsoleSender(), target));

        assertEquals(List.of("Target example"), opened);
    }

    @Test
    void consoleCannotOpenAMenuForItself() {
        assertThrows(
                CommandSyntaxException.class,
                () -> dispatcher.execute("foliate menu", new CommandSource(server.getConsoleSender())));
    }

    @Test
    void everyoneMayOpenTheMenu() {
        assertEquals(PermissionDefault.TRUE, server.getPluginManager().getPermission(MenuCommand.PERMISSION).getDefault());
    }
}
