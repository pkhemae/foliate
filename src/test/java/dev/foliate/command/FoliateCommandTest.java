package dev.foliate.command;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import dev.foliate.FoliatePlugin;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.Schedulers;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

class FoliateCommandTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private CommandDispatcher<CommandSourceStack> dispatcher;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        FoliatePlugin plugin = MockBukkit.load(FoliatePlugin.class);

        ConfigService config = new ConfigService(directory);
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, config, NOPLogger.NOP_LOGGER);
        ModuleManager modules = new ModuleManager(NOPLogger.NOP_LOGGER);
        modules.register(config);
        modules.register(messages);
        assertTrue(modules.enableAll());

        FoliateCommand command =
                new FoliateCommand(modules, messages, new Schedulers(plugin), NOPLogger.NOP_LOGGER, "1.2.3", "Test 26.2");
        dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(command.build());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
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
    void reloadReportsSuccess() throws Exception {
        PlayerMock player = operator();

        dispatcher.execute("foliate reload", new Source(player));
        runAsyncWork();

        assertTrue(plain(player.nextComponentMessage()).contains("reloaded"));
    }

    @Test
    void reloadReportsWhichModuleFailed() throws Exception {
        PlayerMock player = operator();
        Files.writeString(directory.resolve("config.yml"), "debug: [unclosed\n");

        dispatcher.execute("foliate reload", new Source(player));
        runAsyncWork();

        String message = plain(player.nextComponentMessage());
        assertTrue(message.contains("config"), message);
        assertTrue(message.contains("config.yml"), message);
    }

    @Test
    void playerWithoutPermissionCannotSeeTheCommand() {
        PlayerMock player = server.addPlayer();

        assertThrows(CommandSyntaxException.class, () -> dispatcher.execute("foliate info", new Source(player)));
    }

    @Test
    void everyPermissionNodeIsDeclaredInPluginYml() throws Exception {
        for (Field field : Permissions.class.getFields()) {
            String node = (String) field.get(null);

            assertNotNull(server.getPluginManager().getPermission(node), node + " is not declared in build.gradle.kts");
        }
    }

    private PlayerMock operator() {
        PlayerMock player = server.addPlayer();
        player.setOp(true);
        return player;
    }

    private void runAsyncWork() {
        server.getScheduler().performTicks(1);
        server.getScheduler().waitAsyncTasksFinished();
    }

    private static String plain(Component component) {
        assertNotNull(component, "no message was sent");
        return PlainTextComponentSerializer.plainText().serialize(component);
    }

    private record Source(CommandSender sender) implements CommandSourceStack {

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
            return sender instanceof Entity entity ? entity : null;
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
