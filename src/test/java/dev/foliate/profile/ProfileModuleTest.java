package dev.foliate.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleSkippedException;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.storage.Database;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

class ProfileModuleTest {

    private static final Clock CLOCK = Clock.fixed(Instant.ofEpochMilli(1_000), ZoneOffset.UTC);

    @TempDir
    Path directory;

    private ServerMock server;
    private ConfigService config;
    private MessageService messages;
    private Database database;
    private ProfileModule profiles;
    private final PlaceholderRegistry placeholders = new PlaceholderRegistry();
    private final ProfileRepository repository = new ProfileRepository();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        config = new ConfigService(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, config, NOPLogger.NOP_LOGGER);
        messages.enable();
        database = new Database(directory, config, NOPLogger.NOP_LOGGER);
        database.enable();
        profiles = newModule();
        profiles.enable();
    }

    @AfterEach
    void tearDown() {
        profiles.disable();
        database.disable();
        MockBukkit.unmock();
    }

    @Test
    void firstJoinCreatesAProfileAndCountsTheJoin() {
        PlayerMock player = server.addPlayer("Alex");

        PlayerProfile profile = profiles.find(player.getUniqueId()).orElseThrow();
        assertEquals("Alex", profile.name());
        assertEquals(1, profile.joins());
        assertEquals(1_000, profile.firstSeen());
    }

    @Test
    void profileIsSavedOnQuitAndLeavesTheCache() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();

        player.disconnect();
        flush();

        assertTrue(profiles.find(id).isEmpty());
        assertEquals(1, stored(id).orElseThrow().joins());
    }

    @Test
    void returningPlayerKeepsCountingFromTheStoredProfile() throws Exception {
        UUID id = UUID.randomUUID();
        database.call(connection -> {
            repository.save(connection, new PlayerProfile(id, "OldName", 10, 20, 4));
            return null;
        });

        PlayerMock player = new PlayerMock(server, "NewName", id);
        server.addPlayer(player);

        PlayerProfile profile = profiles.find(id).orElseThrow();
        assertEquals(5, profile.joins());
        assertEquals("NewName", profile.name());
        assertEquals(10, profile.firstSeen());
    }

    @Test
    void shutdownSavesPlayersStillOnline() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();

        profiles.disable();

        assertEquals(1, stored(id).orElseThrow().joins());
    }

    @Test
    void joinsPlaceholderReadsTheCachedProfile() {
        PlayerMock player = server.addPlayer("Alex");

        assertEquals("1", placeholders.resolve("joins", player));
        assertEquals(null, placeholders.resolve("joins", null));
    }

    @Test
    void moduleIsSkippedWithoutADatabase() throws Exception {
        Path other = Files.createDirectory(directory.resolve("other"));
        ConfigService otherConfig = new ConfigService(other, NOPLogger.NOP_LOGGER);
        otherConfig.enable();
        Database stopped = new Database(other, otherConfig, NOPLogger.NOP_LOGGER);
        ProfileModule module = new ProfileModule(
                MockBukkit.createMockPlugin(), stopped, messages, placeholders, NOPLogger.NOP_LOGGER, CLOCK);

        assertThrows(ModuleSkippedException.class, module::enable);
        assertFalse(stopped.isRunning());
    }

    private ProfileModule newModule() {
        return new ProfileModule(
                MockBukkit.createMockPlugin(), database, messages, placeholders, NOPLogger.NOP_LOGGER, CLOCK);
    }

    /** Waits for the writes queued on the storage threads. */
    private void flush() throws Exception {
        database.submit(connection -> null).get();
    }

    private Optional<PlayerProfile> stored(UUID id) throws Exception {
        return database.call(connection -> repository.find(connection, id));
    }
}
