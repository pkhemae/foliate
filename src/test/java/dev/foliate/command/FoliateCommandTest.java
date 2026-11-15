package dev.foliate.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.foliate.FoliatePlugin;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.FoliaSchedulers;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
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

class FoliateCommandTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private FoliatePlugin plugin;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private CommandDispatcher<CommandSourceStack> withoutMenus;
    private final List<String> opened = new ArrayList<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // The real plugin is loaded for its plugin.yml (which declares the permissions) and to
        // check that it registers the command.
        plugin = MockBukkit.load(FoliatePlugin.class);

        ConfigService config = new ConfigService(directory, NOPLogger.NOP_LOGGER);
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, config, NOPLogger.NOP_LOGGER);
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

        FoliateCommand command =
                new FoliateCommand(modules, messages, new FoliaSchedulers(plugin), NOPLogger.NOP_LOGGER, "1.2.3", "Test 26.2",
                (player, menu) -> opened.add(player.getName() + " " + menu));
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());
        withoutMenus = new CommandDispatcher<>();
        withoutMenus.getRoot().addChild(new FoliateCommand(
                        modules, messages, new FoliaSchedulers(plugin), NOPLogger.NOP_LOGGER, "1.2.3", "Test 26.2", null)
                .build());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginRegistersTheCommandWithTheServer() {
        assertTrue(server.dispatchCommand(server.getConsoleSender(), "foliate info"));
    }

    @Test
    void infoShowsVersionPlatformAndModules() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate info", new Source(player));

        String message = plain(player.nextComponentMessage());
        assertTrue(message.contains("1.2.3"), message);
        assertTrue(message.contains("Test 26.2"), message);
        assertTrue(message.contains("config, messages"), message);
    }

    @Test
    void reloadAnswersAtOnceThenReportsSuccessFromAnotherThread() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate reload", new Source(player));

        assertTrue(plain(player.nextComponentMessage()).contains("Reloading"));
        assertNull(player.nextComponentMessage(), "the reload must not have finished on the calling thread");

        runAsyncWork();
        assertTrue(plain(player.nextComponentMessage()).contains("reloaded"));
    }

    @Test
    void reloadReportsWhichModuleFailedAndWhyOnOneLine() throws Exception {
        PlayerMock player = operator();
        Files.writeString(directory.resolve("config.yml"), "debug: [unclosed\n");

        dispatcher.execute("foliate reload", new Source(player));
        player.nextComponentMessage();
        runAsyncWork();

        String message = plain(player.nextComponentMessage());
        assertTrue(message.contains("Could not reload config:"), message);
        assertTrue(message.contains("config.yml is not valid YAML"), message);
        assertEquals(1, message.lines().count(), message);
    }

    @Test
    void unexpectedFailureIsReportedWithoutItsDetails() throws Exception {
        PlayerMock player = operator();
        Files.createFile(directory.resolve("crash"));

        dispatcher.execute("foliate reload", new Source(player));
        player.nextComponentMessage();
        runAsyncWork();

        String message = plain(player.nextComponentMessage());
        assertTrue(message.contains("buggy"), message);
        assertTrue(message.contains("console"), message);
        assertFalse(message.contains("internal detail"), message);
    }

    @Test
    void consoleGetsTheReloadResultToo() throws Exception {
        ConsoleCommandSenderMock console = server.getConsoleSender();

        dispatcher.execute("foliate reload", new Source(console));
        console.nextComponentMessage();
        runAsyncWork();

        assertTrue(plain(console.nextComponentMessage()).contains("reloaded"));
    }

    @Test
    void menuOpensForThePlayerRunningTheCommand() throws Exception {
        // No operator status needed: the menu permission is granted to everyone by default.
        PlayerMock player = server.addPlayer("Alex");

        dispatcher.execute("foliate menu", new Source(player));

        assertEquals(List.of("Alex example"), opened);
    }

    @Test
    void menuOpensForTheExecutorWhenItDiffersFromTheSender() throws Exception {
        PlayerMock target = server.addPlayer("Target");

        // What "/execute as Target run foliate menu" typed in the console looks like.
        dispatcher.execute("foliate menu", new Source(server.getConsoleSender(), target));

        assertEquals(List.of("Target example"), opened);
    }

    @Test
    void consoleCannotOpenAMenuForItself() {
        assertThrows(
                CommandSyntaxException.class,
                () -> dispatcher.execute("foliate menu", new Source(server.getConsoleSender())));
    }

    @Test
    void menuSubcommandDoesNotExistWithoutAMenuPlugin() {
        PlayerMock player = operator();

        assertThrows(CommandSyntaxException.class, () -> withoutMenus.execute("foliate menu", new Source(player)));
    }

    @Test
    void playerWithOnlyTheMenuPermissionCannotUseAdministrationSubcommands() {
        PlayerMock player = server.addPlayer();

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate info", new Source(player)));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate reload", new Source(player)));
    }

    @Test
    void playerWithoutAnyPermissionCannotSeeTheCommand() {
        PlayerMock player = server.addPlayer();

        assertThrows(CommandSyntaxException.class, () -> withoutMenus.execute("foliate info", new Source(player)));
    }

    @Test
    void eachSubcommandChecksItsOwnPermission() throws Exception {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, Permissions.COMMAND_INFO, true);

        dispatcher.execute("foliate info", new Source(player));

        assertNotNull(player.nextComponentMessage());
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate reload", new Source(player)));
    }

    /** Both directions: a constant nobody declared, and a declared node nobody checks. */
    @Test
    void permissionConstantsAndPluginYmlDeclareTheSameNodes() throws Exception {
        Set<String> constants = new TreeSet<>();
        for (Field field : Permissions.class.getFields()) {
            constants.add((String) field.get(null));
        }
        Set<String> declared = new TreeSet<>();
        plugin.getPluginMeta().getPermissions().forEach(permission -> declared.add(permission.getName()));

        assertEquals(constants, declared);
    }

    @Test
    void administrationPermissionsAreForOperatorsOnly() {
        for (String node : List.of(Permissions.COMMAND_INFO, Permissions.COMMAND_RELOAD)) {
            assertEquals(PermissionDefault.OP, server.getPluginManager().getPermission(node).getDefault(), node);
        }
    }

    private PlayerMock operator() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        return player;
    }

    /** Lets the async reload run, then the reply scheduled back on the sender's context. */
    private void runAsyncWork() {
        server.getScheduler().performTicks(1);
        server.getScheduler().waitAsyncTasksFinished();
        server.getScheduler().performTicks(2);
    }

    private static String plain(Component component) {
        assertNotNull(component, "no message was sent");
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private record Source(CommandSender sender, Entity executor) implements CommandSourceStack {

        Source(CommandSender sender) {
            this(sender, sender instanceof Entity entity ? entity : null);
        }

        @Override
        public Location getLocation() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSender getSender() {
            return sender;
        }

        @Override
        public Entity getExecutor() {
            return executor;
        }

        @Override
        public Player getPlayerOrThrow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Entity getEntityOrThrow() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CommandSourceStack withLocation(Location location) {
            return this;
        }

        @Override
        public CommandSourceStack withExecutor(Entity executor) {
            return this;
        }
    }
}
