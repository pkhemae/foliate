package dev.foliate.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.ManualSchedulers;
import dev.foliate.testing.CommandSource;
import dev.foliate.testing.PluginYml;
import dev.foliate.testing.TestCommands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.bukkit.permissions.PermissionDefault;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.command.ConsoleCommandSenderMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/** {@code info} and {@code reload}, the subcommands about the plugin as a whole. */
class AdministrationCommandsTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private Plugin plugin;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private final ManualSchedulers schedulers = new ManualSchedulers();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // The permissions come from the plugin.yml of the build, without starting the real plugin:
        // its modules would listen to the players these tests add. That the plugin registers its
        // commands with the server is checked by the server test on a real server.
        plugin = MockBukkit.createMockPlugin();
        PluginYml.registerPermissions(server);

        ConfigService<PluginConfig> config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);
        ModuleManager modules = new ModuleManager(NOPLogger.NOP_LOGGER);
        modules.register(config);
        modules.register(messages);
        modules.register(new Module() {
            @Override
            public String name() {
                return "buggy";
            }

            @Override
            public void enable() {}

            @Override
            public void reload() {
                if (Files.exists(directory.resolve("crash"))) {
                    throw new IllegalStateException("internal detail that must stay in the console");
                }
            }
        });
        assertTrue(modules.enableAll());

        dispatcher = TestCommands.dispatcher(
                new InfoCommand(modules, messages, "1.2.3", "Test 26.2"),
                new ReloadCommand(plugin, modules, messages, schedulers, NOPLogger.NOP_LOGGER));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void infoShowsVersionPlatformAndModules() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate info", new CommandSource(player));

        String message = TestCommands.plain(player.nextComponentMessage());
        assertTrue(message.contains("1.2.3"), message);
        assertTrue(message.contains("Test 26.2"), message);
        assertTrue(message.contains("config, messages"), message);
    }

    @Test
    void reloadAnswersAtOnceThenReportsSuccessFromThePlayersOwnThread() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate reload", new CommandSource(player));

        assertTrue(TestCommands.plain(player.nextComponentMessage()).contains("Reloading"));
        assertNull(player.nextComponentMessage(), "the reload must not have finished on the calling thread");

        runAsyncWork();
        assertTrue(TestCommands.plain(player.nextComponentMessage()).contains("reloaded"));
        // Reloaded off the tick threads, then answered from the region that owns the player.
        assertEquals(List.of("async", "entity"), schedulers.contextsUsed());
    }

    @Test
    void reloadReportsWhichModuleFailedAndWhyOnOneLine() throws Exception {
        PlayerMock player = operator();
        Files.writeString(directory.resolve("config.yml"), "debug: [unclosed\n");

        dispatcher.execute("foliate reload", new CommandSource(player));
        player.nextComponentMessage();
        runAsyncWork();

        String message = TestCommands.plain(player.nextComponentMessage());
        assertTrue(message.contains("Could not reload config:"), message);
        assertTrue(message.contains("config.yml is not valid YAML"), message);
        assertEquals(1, message.lines().count(), message);
    }

    @Test
    void unexpectedFailureIsReportedWithoutItsDetails() throws Exception {
        PlayerMock player = operator();
        Files.createFile(directory.resolve("crash"));

        dispatcher.execute("foliate reload", new CommandSource(player));
        player.nextComponentMessage();
        runAsyncWork();

        String message = TestCommands.plain(player.nextComponentMessage());
        assertTrue(message.contains("buggy"), message);
        assertTrue(message.contains("console"), message);
        assertFalse(message.contains("internal detail"), message);
    }

    @Test
    void consoleGetsTheReloadResultToo() throws Exception {
        ConsoleCommandSenderMock console = server.getConsoleSender();

        dispatcher.execute("foliate reload", new CommandSource(console));
        console.nextComponentMessage();
        runAsyncWork();

        assertTrue(TestCommands.plain(console.nextComponentMessage()).contains("reloaded"));
        assertEquals(List.of("async", "global"), schedulers.contextsUsed());
    }

    /** Disabling the plugin interrupts the reload: its outcome is not worth an answer then. */
    @Test
    void reloadFinishingAfterThePluginWasDisabledAnswersNothing() throws Exception {
        PlayerMock player = operator();
        dispatcher.execute("foliate reload", new CommandSource(player));
        player.nextComponentMessage();

        server.getPluginManager().disablePlugin(plugin);
        runAsyncWork();

        assertNull(player.nextComponentMessage());
        // Nothing may be scheduled for a disabled plugin: the server would refuse it.
        assertEquals(List.of("async"), schedulers.contextsUsed());
    }

    @Test
    void playerWithoutTheirPermissionsCannotUseThem() {
        PlayerMock player = server.addPlayer();

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate info", new CommandSource(player)));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate reload", new CommandSource(player)));
    }

    @Test
    void eachSubcommandChecksItsOwnPermission() throws Exception {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, InfoCommand.PERMISSION, true);

        dispatcher.execute("foliate info", new CommandSource(player));

        assertNotNull(player.nextComponentMessage());
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate reload", new CommandSource(player)));
    }

    @Test
    void administrationPermissionsAreForOperatorsOnly() {
        for (String node : List.of(InfoCommand.PERMISSION, ReloadCommand.PERMISSION)) {
            assertEquals(PermissionDefault.OP, server.getPluginManager().getPermission(node).getDefault(), node);
        }
    }

    private PlayerMock operator() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        return player;
    }

    /** Runs the reload on its async context, then the reply it schedules back to the sender. */
    private void runAsyncWork() {
        schedulers.tick(1);
        schedulers.tick(1);
    }
}
