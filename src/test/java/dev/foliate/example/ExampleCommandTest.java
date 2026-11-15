package dev.foliate.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.scheduler.ManualSchedulers;
import java.nio.file.Files;
import java.nio.file.Path;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * Checks that each handler does its work and reports back. Everything runs on one thread here, so
 * that the work happens on the right region is for a Folia server to tell.
 */
class ExampleCommandTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private ExampleCommand command;
    private final ManualSchedulers schedulers = new ManualSchedulers();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        ConfigService config = new ConfigService(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        MessageService messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, config, NOPLogger.NOP_LOGGER);
        messages.enable();
        command = new ExampleCommand(schedulers, messages, directory);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void feedingHappensLaterOnTheTargetsSchedulerThenTheSenderIsTold() {
        PlayerMock sender = server.addPlayer("Sender");
        PlayerMock target = server.addPlayer("Target");
        target.setFoodLevel(3);

        command.feed(sender, target);

        assertEquals(3, target.getFoodLevel(), "the target must not be touched from the sender's thread");
        schedulers.tick(2);
        assertEquals(20, target.getFoodLevel());
        assertTrue(plain(sender.nextComponentMessage()).contains("Fed Target"));
    }

    @Test
    void feedingATargetWhoLeftTellsTheSender() {
        PlayerMock sender = server.addPlayer("Sender");
        PlayerMock target = server.addPlayer("Target");
        schedulers.retire(target);

        command.feed(sender, target);
        schedulers.tick(2);

        assertTrue(plain(sender.nextComponentMessage()).contains("no longer online"));
    }

    @Test
    void nothingIsSentToASenderWhoLeft() {
        PlayerMock sender = server.addPlayer("Sender");
        PlayerMock target = server.addPlayer("Target");

        command.feed(sender, target);
        schedulers.retire(sender);
        schedulers.tick(2);

        assertNull(sender.nextComponentMessage());
    }

    @Test
    void blockIsReadAndReported() {
        PlayerMock sender = server.addPlayer("Sender");
        World world = sender.getWorld();
        Location location = new Location(world, 4, 70, -2);
        location.getBlock().setType(Material.GOLD_BLOCK);

        command.block(sender, location);
        schedulers.tick(2);

        String message = plain(sender.nextComponentMessage());
        assertTrue(message.contains("4 70 -2"), message);
        assertTrue(message.contains("gold_block"), message);
    }

    @Test
    void worldTimeIsReported() {
        PlayerMock sender = server.addPlayer("Sender");
        sender.getWorld().setFullTime(4321);

        command.time(sender, sender.getWorld());
        schedulers.tick(2);

        assertTrue(plain(sender.nextComponentMessage()).contains("4321"));
    }

    @Test
    void noteIsAppendedToTheFile() throws Exception {
        PlayerMock sender = server.addPlayer("Sender");

        command.note(sender, "first");
        command.note(sender, "second");
        schedulers.tick(2);

        assertEquals("Sender: first\nSender: second\n", Files.readString(directory.resolve("example-notes.txt")).replace("\r\n", "\n"));
        assertTrue(plain(sender.nextComponentMessage()).contains("Note saved"));
    }

    @Test
    void consoleGetsItsRepliesToo() {
        command.time(server.getConsoleSender(), server.addSimpleWorld("world"));
        schedulers.tick(2);

        assertTrue(plain(server.getConsoleSender().nextComponentMessage()).contains("World time"));
    }

    private static String plain(Component component) {
        return component == null ? "" : PlainTextComponentSerializer.plainText().serialize(component);
    }
}
