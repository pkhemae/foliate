package dev.foliate.core.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.suggestion.Suggestion;
import dev.foliate.testing.CommandSource;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** A player the sender cannot see is, for that sender, not online: neither suggested nor found. */
class OnlinePlayersTest {

    private ServerMock server;
    private Plugin plugin;
    private PlayerMock alex;
    private PlayerMock alice;
    private PlayerMock bob;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        plugin = MockBukkit.createMockPlugin();
        alex = server.addPlayer("Alex");
        alice = server.addPlayer("Alice");
        bob = server.addPlayer("Bob");
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void theNamesThatStartWithWhatIsTypedAreSuggested() throws Exception {
        assertEquals(List.of("Alex", "Alice"), suggested(bob, "al"));
        assertEquals(List.of("Alex", "Alice", "Bob"), suggested(server.getConsoleSender(), ""));
    }

    @Test
    void aPlayerTheSenderCannotSeeIsNeitherSuggestedNorFound() throws Exception {
        bob.hidePlayer(plugin, alice);

        assertEquals(List.of("Alex"), suggested(bob, "al"));
        assertNull(OnlinePlayers.find(server, bob, "Alice"));
        // Others still see her, and so does the console.
        assertSame(alice, OnlinePlayers.find(server, alex, "Alice"));
        assertSame(alice, OnlinePlayers.find(server, server.getConsoleSender(), "Alice"));
    }

    @Test
    void aNameIsFoundWhateverItsCaseAndOnlyWhole() {
        assertSame(alice, OnlinePlayers.find(server, bob, "alice"));
        assertNull(OnlinePlayers.find(server, bob, "Ali"));
        assertNull(OnlinePlayers.find(server, bob, "Steve"));
    }

    private List<String> suggested(CommandSender sender, String typed) throws Exception {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(Commands.literal("test")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(OnlinePlayers.suggestions(server))
                        .executes(context -> 1)));
        String input = "test " + typed;
        return dispatcher.getCompletionSuggestions(dispatcher.parse(input, new CommandSource(sender)))
                .get()
                .getList()
                .stream()
                .map(Suggestion::getText)
                .sorted()
                .toList();
    }
}
