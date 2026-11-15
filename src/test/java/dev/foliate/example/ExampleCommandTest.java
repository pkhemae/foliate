package dev.foliate.example;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.scheduler.ManualSchedulers;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
        command = new ExampleCommand(schedulers, messages, directory, NOPLogger.NOP_LOGGER);
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
        // The work on the target's scheduler, the reply on the sender's.
        assertEquals(List.of("entity", "entity"), schedulers.contextsUsed());
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
        world.loadChunk(0, -1);
        location.getBlock().setType(Material.GOLD_BLOCK);

        command.block(sender, location);
        schedulers.tick(2);

        String message = plain(sender.nextComponentMessage());
        assertTrue(message.contains("4 70 -2"), message);
        assertTrue(message.contains("gold_block"), message);
        assertEquals("region", schedulers.contextsUsed().getFirst());
    }

    @Test
    void blockInAChunkThatIsNotLoadedIsNotLoadedForTheOccasion() {
        PlayerMock sender = server.addPlayer("Sender");
        World world = sender.getWorld();
        Location far = new Location(world, 50_000, 70, 50_000);

        command.block(sender, far);
        schedulers.tick(2);

        assertTrue(plain(sender.nextComponentMessage()).contains("Nothing is loaded"));
        assertFalse(world.isChunkLoaded(50_000 >> 4, 50_000 >> 4));
    }

    @Test
    void failureIsReportedInsteadOfLeavingTheSenderWaiting() throws Exception {
        PlayerMock sender = server.addPlayer("Sender");
        // A directory where the notes file should be: the write fails.
        Files.createDirectory(directory.resolve("example-notes.txt"));

        command.note(sender, "lost");
        schedulers.tick(2);

        assertTrue(plain(sender.nextComponentMessage()).contains("Something went wrong"));
    }

    @Test
    void overlongNoteIsCutBeforeItReachesTheDisk() throws Exception {
        PlayerMock sender = server.addPlayer("Sender");

        command.note(sender, "x".repeat(5_000));
        schedulers.tick(2);

        assertTrue(Files.size(directory.resolve("example-notes.txt")) < 300);
    }

    @Test
    void worldTimeIsReported() {
        PlayerMock sender = server.addPlayer("Sender");
        sender.getWorld().setFullTime(4321);

        command.time(sender, sender.getWorld());
        schedulers.tick(2);

        assertTrue(plain(sender.nextComponentMessage()).contains("4321"));
        assertEquals("global", schedulers.contextsUsed().getFirst());
    }

    @Test
    void noteIsAppendedToTheFile() throws Exception {
        PlayerMock sender = server.addPlayer("Sender");

        command.note(sender, "first");
        command.note(sender, "second");
        schedulers.tick(2);

        assertEquals("Sender: first\nSender: second\n", Files.readString(directory.resolve("example-notes.txt")).replace("\r\n", "\n"));
        assertTrue(plain(sender.nextComponentMessage()).contains("Note saved"));
        assertEquals("async", schedulers.contextsUsed().getFirst());
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
