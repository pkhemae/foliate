package dev.foliate.hooks.zmenu;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.mojang.brigadier.CommandDispatcher;
import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.testing.CommandSource;
import dev.foliate.testing.PluginYml;
import dev.foliate.testing.TestCommands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/** {@code /foliate menu}, with a menu plugin of the test's own that records what it opens. */
class MenuCommandTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private final List<String> opened = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        PluginYml.registerPermissions(server);
        ConfigService<PluginConfig> config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);
        messages.enable();
        dispatcher = TestCommands.registerExecuteAs(
                TestCommands.dispatcher(new MenuCommand((player, menu) -> opened.add(player.getName() + " " + menu), messages)),
                server);
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
    void consoleOpensTheMenuForThePlayerItRunsTheCommandAs() throws Exception {
        server.addPlayer("Target");

        dispatcher.execute("execute as Target run foliate menu", new CommandSource(server.getConsoleSender()));

        assertEquals(List.of("Target example"), opened);
    }

    @Test
    void consoleIsToldToRunTheCommandAsAPlayer() throws Exception {
        ConsoleCommandSenderMock console = server.getConsoleSender();

        dispatcher.execute("foliate menu", new CommandSource(console));

        assertEquals(List.of(), opened);
        assertEquals(
                "[Foliate] Only a player can open a menu: /execute as <player> run foliate menu",
                TestCommands.plain(console.nextComponentMessage()));
    }

    @Test
    void everyoneMayOpenTheMenu() {
        assertEquals(PermissionDefault.TRUE, server.getPluginManager().getPermission(MenuCommand.PERMISSION).getDefault());
    }
}
