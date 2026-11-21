package dev.foliate.modules.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.testing.CommandSource;
import dev.foliate.testing.PluginYml;
import dev.foliate.testing.TestCommands;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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

/** {@code /foliate profile}, with profiles of the test's own instead of the module's cache. */
class ProfileCommandTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private Plugin plugin;
    private CommandDispatcher<CommandSourceStack> dispatcher;
    private final Map<UUID, PlayerProfile> profiles = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        PluginYml.registerPermissions(server);
        ConfigService<PluginConfig> config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);
        messages.enable();
        dispatcher = TestCommands.foliate(
                new ProfileCommand(id -> Optional.ofNullable(profiles.get(id)), messages, server));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void playerSeesTheirOwnProfileWithoutAnyPermissionBeyondTheDefault() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        profiles.put(player.getUniqueId(), profile(player, "Alex", 1_700_000_000_000L, 1_700_003_600_000L, 7));

        dispatcher.execute("foliate profile", new CommandSource(player));

        String message = TestCommands.plain(player.nextComponentMessage());
        assertEquals(
                "[Foliate] Alex\nFirst seen: 2023-11-14 22:13 UTC\nOnline since: 2023-11-14 23:13 UTC\nJoins: 7",
                message.replace("\r", ""));
    }

    @Test
    void profileOfAnotherPlayerNeedsItsOwnPermission() throws Exception {
        PlayerMock alex = server.addPlayer("Alex");
        PlayerMock target = server.addPlayer("Target");
        profiles.put(target.getUniqueId(), profile(target, "Target", 10, 20, 3));

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate profile Target", new CommandSource(alex)));

        alex.addAttachment(plugin, ProfileCommand.PERMISSION_OTHERS, true);
        dispatcher.execute("foliate profile Target", new CommandSource(alex));
        assertTrue(TestCommands.plain(alex.nextComponentMessage()).contains("Target"));
    }

    @Test
    void profileOfAPlayerWhoIsNotOnlineIsReported() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate profile Nobody", new CommandSource(player));

        assertEquals("[Foliate] Nobody is not online.", TestCommands.plain(player.nextComponentMessage()));
    }

    @Test
    void onlinePlayerWithoutALoadedProfileIsReportedToo() throws Exception {
        PlayerMock player = server.addPlayer("Alex");

        dispatcher.execute("foliate profile", new CommandSource(player));

        assertEquals("[Foliate] The profile of Alex is not loaded yet.", TestCommands.plain(player.nextComponentMessage()));
    }

    @Test
    void runningTheCommandAsSomeoneElseNeedsThePermissionToSeeOthers() throws Exception {
        PlayerMock alex = server.addPlayer("Alex");
        PlayerMock target = server.addPlayer("Target");
        profiles.put(target.getUniqueId(), profile(target, "Target", 10, 20, 3));

        // What "/execute as Target run foliate profile" typed by Alex looks like.
        assertThrows(
                CommandSyntaxException.class,
                () -> dispatcher.execute("foliate profile", new CommandSource(alex, target)));

        alex.addAttachment(plugin, ProfileCommand.PERMISSION_OTHERS, true);
        dispatcher.execute("foliate profile", new CommandSource(alex, target));
        assertTrue(TestCommands.plain(alex.nextComponentMessage()).contains("Joins: 3"));
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

        assertTrue(TestCommands.plain(console.nextComponentMessage()).contains("Name a player: /foliate profile <player>"));
    }

    /**
     * Without an executor (a command block, a console with restricted permissions) there is no
     * one else's profile to show: the sender is asked for a name, not refused by Brigadier.
     */
    @Test
    void senderWithoutAnExecutorIsAskedForANameWhateverItsPermissions() throws Exception {
        PlayerMock withoutOthers = server.addPlayer("Alex");

        dispatcher.execute("foliate profile", new CommandSource(withoutOthers, null));

        assertTrue(TestCommands.plain(withoutOthers.nextComponentMessage()).contains("Name a player: /foliate profile <player>"));
    }

    /** A player the sender cannot see, such as one who vanished, is neither suggested nor shown. */
    @Test
    void hiddenPlayersAreNeitherSuggestedNorShown() throws Exception {
        PlayerMock operator = operator();
        PlayerMock hidden = server.addPlayer("Alice");
        profiles.put(hidden.getUniqueId(), profile(hidden, "Alice", 10, 20, 3));
        operator.hidePlayer(MockBukkit.createMockPlugin(), hidden);

        var suggestions = dispatcher
                .getCompletionSuggestions(dispatcher.parse("foliate profile a", new CommandSource(operator)))
                .get();
        dispatcher.execute("foliate profile Alice", new CommandSource(operator));

        assertTrue(suggestions.isEmpty(), suggestions.getList().toString());
        assertEquals("[Foliate] Alice is not online.", TestCommands.plain(operator.nextComponentMessage()));
    }

    @Test
    void consoleCanShowAnOnlinePlayersProfile() throws Exception {
        PlayerMock target = server.addPlayer("Target");
        profiles.put(target.getUniqueId(), profile(target, "Target", 10, 20, 3));
        ConsoleCommandSenderMock console = server.getConsoleSender();

        dispatcher.execute("foliate profile Target", new CommandSource(console));

        assertTrue(TestCommands.plain(console.nextComponentMessage()).contains("Joins: 3"));
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
    void seeingOtherPlayersIsForOperatorsAndOwnProfileForEveryone() {
        assertEquals(PermissionDefault.TRUE, server.getPluginManager().getPermission(ProfileCommand.PERMISSION).getDefault());
        assertEquals(PermissionDefault.OP, server.getPluginManager().getPermission(ProfileCommand.PERMISSION_OTHERS).getDefault());
    }

    /** While the player is online, lastSeen holds the time of this join. */
    private static PlayerProfile profile(Player player, String name, long firstSeen, long joinedAt, int joins) {
        return new PlayerProfile(player.getUniqueId(), name, firstSeen, joinedAt, joins);
    }

    private PlayerMock operator() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        return player;
    }
}
