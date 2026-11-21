package dev.foliate.commands;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.foliate.PluginConfig;
import dev.foliate.contracts.profile.ProfileSummary;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.ManualSchedulers;
import dev.foliate.testing.CommandSource;
import dev.foliate.testing.PluginYml;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
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

class RootCommandTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private Plugin plugin;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private CommandDispatcher<CommandSourceStack> withoutMenus;
    private final ManualSchedulers schedulers = new ManualSchedulers();
    private final List<String> opened = new ArrayList<>();
    private final Map<UUID, ProfileSummary> profiles = new HashMap<>();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        // The permissions come from the plugin.yml of the build, without starting the real plugin:
        // its modules would listen to the players these tests add. That the plugin registers its
        // commands with the server is checked by the self-test on a real server.
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

        List<Subcommand> administration = List.of(
                new InfoCommand(modules, messages, "1.2.3", "Test 26.2"),
                new ReloadCommand(modules, messages, schedulers, NOPLogger.NOP_LOGGER));
        List<Subcommand> all = new ArrayList<>(administration);
        all.add(new MenuCommand((player, menu) -> opened.add(player.getName() + " " + menu)));
        all.add(new ProfileCommand(id -> Optional.ofNullable(profiles.get(id)), messages, server));
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new RootCommand("foliate", all).build());
        withoutMenus = new CommandDispatcher<>();
        withoutMenus.getRoot().addChild(new RootCommand("foliate", administration).build());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void infoShowsVersionPlatformAndModules() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate info", new CommandSource(player));

        String message = plain(player.nextComponentMessage());
        assertTrue(message.contains("1.2.3"), message);
        assertTrue(message.contains("Test 26.2"), message);
        assertTrue(message.contains("config, messages"), message);
    }

    @Test
    void reloadAnswersAtOnceThenReportsSuccessFromAnotherThread() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate reload", new CommandSource(player));

        assertTrue(plain(player.nextComponentMessage()).contains("Reloading"));
        assertNull(player.nextComponentMessage(), "the reload must not have finished on the calling thread");

        runAsyncWork();
        assertTrue(plain(player.nextComponentMessage()).contains("reloaded"));
    }

    @Test
    void reloadReportsWhichModuleFailedAndWhyOnOneLine() throws Exception {
        PlayerMock player = operator();
        Files.writeString(directory.resolve("config.yml"), "debug: [unclosed\n");

        dispatcher.execute("foliate reload", new CommandSource(player));
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

        dispatcher.execute("foliate reload", new CommandSource(player));
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

        dispatcher.execute("foliate reload", new CommandSource(console));
        console.nextComponentMessage();
        runAsyncWork();

        assertTrue(plain(console.nextComponentMessage()).contains("reloaded"));
    }

    @Test
    void subcommandThatSetsItsOwnRequirementStillNeedsItsPermission() {
        Subcommand restricted = stub("secret", Permissions.COMMAND_RELOAD, node -> node.requires(source -> true)
                .executes(context -> 1));
        CommandDispatcher<CommandSourceStack> commands = new CommandDispatcher<>();
        commands.getRoot().addChild(new RootCommand("foliate", List.of(restricted)).build());
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, Permissions.COMMAND_INFO, true);

        assertThrows(CommandSyntaxException.class, () -> commands.execute("foliate secret", new CommandSource(player)));
    }

    @Test
    void commandIsHiddenWhenItsOnlySubcommandIsUnavailable() {
        Subcommand playersOnly = new MenuCommand((player, menu) -> {});
        CommandDispatcher<CommandSourceStack> commands = new CommandDispatcher<>();
        commands.getRoot().addChild(new RootCommand("foliate", List.of(playersOnly)).build());

        assertThrows(
                CommandSyntaxException.class,
                () -> commands.execute("foliate menu", new CommandSource(server.getConsoleSender())));
    }

    @Test
    void mistakesInTheListOfSubcommandsAreCaughtAtStartup() {
        Subcommand first = stub("same", Permissions.COMMAND_INFO, node -> node.executes(context -> 1));
        Subcommand second = stub("same", Permissions.COMMAND_RELOAD, node -> node.executes(context -> 1));
        Subcommand idle = stub("idle", Permissions.COMMAND_INFO, node -> {});

        assertThrows(IllegalArgumentException.class, () -> new RootCommand("foliate", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new RootCommand("foliate", List.of(first, second)));
        assertThrows(IllegalStateException.class, new RootCommand("foliate", List.of(idle))::build);
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
    void playerSeesTheirOwnProfileWithoutAnyPermissionBeyondTheDefault() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        profiles.put(player.getUniqueId(), new ProfileSummary("Alex", 1_700_000_000_000L, 1_700_003_600_000L, 7));

        dispatcher.execute("foliate profile", new CommandSource(player));

        String message = plain(player.nextComponentMessage());
        assertEquals(
                "[Foliate] Alex\nFirst seen: 2023-11-14 22:13 UTC\nOnline since: 2023-11-14 23:13 UTC\nJoins: 7",
                message.replace("\r", ""));
    }

    @Test
    void profileOfAnotherPlayerNeedsItsOwnPermission() throws Exception {
        PlayerMock alex = server.addPlayer("Alex");
        PlayerMock target = server.addPlayer("Target");
        profiles.put(target.getUniqueId(), new ProfileSummary("Target", 10, 20, 3));

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate profile Target", new CommandSource(alex)));

        alex.addAttachment(plugin, Permissions.COMMAND_PROFILE_OTHERS, true);
        dispatcher.execute("foliate profile Target", new CommandSource(alex));
        assertTrue(plain(alex.nextComponentMessage()).contains("Target"));
    }

    @Test
    void profileOfAPlayerWhoIsNotOnlineIsReported() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate profile Nobody", new CommandSource(player));

        assertEquals("[Foliate] Nobody is not online.", plain(player.nextComponentMessage()));
    }

    @Test
    void onlinePlayerWithoutALoadedProfileIsReportedToo() throws Exception {
        PlayerMock player = server.addPlayer("Alex");

        dispatcher.execute("foliate profile", new CommandSource(player));

        assertEquals("[Foliate] The profile of Alex is not loaded yet.", plain(player.nextComponentMessage()));
    }

    @Test
    void runningTheCommandAsSomeoneElseNeedsThePermissionToSeeOthers() throws Exception {
        PlayerMock alex = server.addPlayer("Alex");
        PlayerMock target = server.addPlayer("Target");
        profiles.put(target.getUniqueId(), new ProfileSummary("Target", 10, 20, 3));

        // What "/execute as Target run foliate profile" typed by Alex looks like.
        assertThrows(
                CommandSyntaxException.class,
                () -> dispatcher.execute("foliate profile", new CommandSource(alex, target)));

        alex.addAttachment(plugin, Permissions.COMMAND_PROFILE_OTHERS, true);
        dispatcher.execute("foliate profile", new CommandSource(alex, target));
        assertTrue(plain(alex.nextComponentMessage()).contains("Joins: 3"));
    }

    @Test
    void playerWithoutThePermissionToSeeOthersIsNotSuggestedAnyone() throws Exception {
        PlayerMock alex = server.addPlayer("Alex");
        server.addPlayer("Bob");

        var suggestions = dispatcher
                .getCompletionSuggestions(dispatcher.parse("foliate profile ", new CommandSource(alex)))
                .get();

        assertTrue(suggestions.isEmpty());
    }

    @Test
    void consoleMustNameAPlayer() throws Exception {
        ConsoleCommandSenderMock console = server.getConsoleSender();

        dispatcher.execute("foliate profile", new CommandSource(console));

        assertTrue(plain(console.nextComponentMessage()).contains("Name a player: /foliate profile <player>"));
    }

    @Test
    void consoleCanShowAnOnlinePlayersProfile() throws Exception {
        PlayerMock target = server.addPlayer("Target");
        profiles.put(target.getUniqueId(), new ProfileSummary("Target", 10, 20, 3));
        ConsoleCommandSenderMock console = server.getConsoleSender();

        dispatcher.execute("foliate profile Target", new CommandSource(console));

        assertTrue(plain(console.nextComponentMessage()).contains("Joins: 3"));
    }

    @Test
    void onlinePlayersAreSuggestedWhileTyping() throws Exception {
        server.addPlayer("Alex");
        server.addPlayer("Bob");
        PlayerMock operator = operator();

        var suggestions = dispatcher
                .getCompletionSuggestions(dispatcher.parse("foliate profile a", new CommandSource(operator)))
                .get();

        assertEquals(
                List.of("Alex"),
                suggestions.getList().stream().map(suggestion -> suggestion.getText()).toList());
    }

    @Test
    void menuSubcommandDoesNotExistWithoutAMenuPlugin() {
        PlayerMock player = operator();

        assertThrows(CommandSyntaxException.class, () -> withoutMenus.execute("foliate menu", new CommandSource(player)));
    }

    @Test
    void playerWithOnlyTheMenuPermissionCannotUseAdministrationSubcommands() {
        PlayerMock player = server.addPlayer();

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate info", new CommandSource(player)));
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate reload", new CommandSource(player)));
    }

    @Test
    void playerWithoutAnyPermissionCannotSeeTheCommand() {
        PlayerMock player = server.addPlayer();

        assertThrows(CommandSyntaxException.class, () -> withoutMenus.execute("foliate info", new CommandSource(player)));
    }

    @Test
    void eachSubcommandChecksItsOwnPermission() throws Exception {
        PlayerMock player = server.addPlayer();
        player.addAttachment(plugin, Permissions.COMMAND_INFO, true);

        dispatcher.execute("foliate info", new CommandSource(player));

        assertNotNull(player.nextComponentMessage());
        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate reload", new CommandSource(player)));
    }

    /** Both directions: a constant nobody declared, and a declared node nobody checks. */
    @Test
    void permissionConstantsAndPluginYmlDeclareTheSameNodes() throws Exception {
        Set<String> constants = new TreeSet<>();
        for (Field field : Permissions.class.getFields()) {
            constants.add((String) field.get(null));
        }
        Set<String> declared = new TreeSet<>();
        declared.addAll(PluginYml.permissions());

        assertEquals(constants, declared);
    }

    @Test
    void administrationPermissionsAreForOperatorsOnly() {
        for (String node : List.of(Permissions.COMMAND_INFO, Permissions.COMMAND_RELOAD, Permissions.COMMAND_PROFILE_OTHERS)) {
            assertEquals(PermissionDefault.OP, server.getPluginManager().getPermission(node).getDefault(), node);
        }
    }

    private static Subcommand stub(
            String name, String permission, Consumer<LiteralArgumentBuilder<CommandSourceStack>> build) {
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
            public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
                build.accept(node);
            }
        };
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

    private static String plain(Component component) {
        assertNotNull(component, "no message was sent");
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
