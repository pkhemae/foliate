package dev.foliate.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

class ProfileModuleTest {

    @TempDir
    Path directory;

    private ServerMock server;
    private MessageService messages;
    private Database database;
    private ProfileModule profiles;
    private final TestClock clock = new TestClock(1_000);
    private final PlaceholderRegistry placeholders = new PlaceholderRegistry();
    private final ProfileRepository repository = new ProfileRepository();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        ConfigService config = new ConfigService(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, config, NOPLogger.NOP_LOGGER);
        messages.enable();
        database = new Database(directory, config, NOPLogger.NOP_LOGGER);
        database.enable();
        profiles = new ProfileModule(
                MockBukkit.createMockPlugin(), database, messages, placeholders, NOPLogger.NOP_LOGGER, clock);
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
    void quitSavesWhenThePlayerWasLastSeenAndEmptiesTheCache() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();

        clock.set(5_000);
        player.disconnect();
        flush();

        assertTrue(profiles.find(id).isEmpty());
        assertEquals(5_000, stored(id).orElseThrow().lastSeen());
    }

    @Test
    void shutdownSavesPlayersStillOnline() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();

        clock.set(5_000);
        profiles.disable();

        assertEquals(5_000, stored(id).orElseThrow().lastSeen());
    }

    @Test
    void returningPlayerKeepsCountingFromTheStoredProfile() throws Exception {
        UUID id = store(new PlayerProfile(UUID.randomUUID(), "OldName", 10, 20, 4));

        server.addPlayer(new PlayerMock(server, "NewName", id));

        PlayerProfile profile = profiles.find(id).orElseThrow();
        assertEquals(5, profile.joins());
        assertEquals("NewName", profile.name());
        assertEquals(10, profile.firstSeen());
    }

    /** The server's order for a second login: pre-login of the new one, quit of the old, join. */
    @Test
    void loggingInAgainWhileOnlineKeepsTheProfile() throws Exception {
        UUID id = store(new PlayerProfile(UUID.randomUUID(), "Alex", 10, 20, 4));
        profiles.beginLogin(id, "Alex");
        profiles.join(id, "Alex");

        profiles.beginLogin(id, "Alex");
        profiles.quit(id);
        profiles.join(id, "Alex");
        flush();

        assertEquals(6, profiles.find(id).orElseThrow().joins());
        assertEquals(6, stored(id).orElseThrow().joins());
    }

    /** A login must read behind the saves still queued for the player, not ahead of them. */
    @Test
    void loginWhileEarlierSavesAreStillQueuedSeesThem() throws Exception {
        UUID id = UUID.randomUUID();
        profiles.beginLogin(id, "Alex");
        CountDownLatch release = holdStorageThread();
        profiles.join(id, "Alex");
        profiles.quit(id);

        CompletableFuture<Void> secondLogin = CompletableFuture.runAsync(() -> {
            try {
                profiles.beginLogin(id, "Alex");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        release.countDown();
        secondLogin.get(10, TimeUnit.SECONDS);
        profiles.join(id, "Alex");
        flush();

        assertEquals(2, stored(id).orElseThrow().joins());
    }

    @Test
    void loginThatNeverBecomesAJoinLeavesNothingBehind() throws Exception {
        UUID id = UUID.randomUUID();

        profiles.beginLogin(id, "Banned");
        profiles.abandonLogin(id);
        profiles.disable();

        assertTrue(stored(id).isEmpty());
    }

    @Test
    void joinWithoutAPreLoginWaitsForTheStoredProfileInsteadOfResettingIt() throws Exception {
        UUID id = store(new PlayerProfile(UUID.randomUUID(), "Alex", 10, 20, 4));

        profiles.join(id, "Alex");
        flush();

        PlayerProfile profile = stored(id).orElseThrow();
        assertEquals(5, profile.joins());
        assertEquals(10, profile.firstSeen());
    }

    @Test
    void savesOfOnePlayerLandInTheOrderTheyWereMade() throws Exception {
        UUID id = UUID.randomUUID();

        for (int session = 0; session < 25; session++) {
            clock.set(1_000 + session);
            profiles.beginLogin(id, "Alex");
            profiles.join(id, "Alex");
            profiles.quit(id);
        }
        flush();

        PlayerProfile profile = stored(id).orElseThrow();
        assertEquals(25, profile.joins());
        assertEquals(1_024, profile.lastSeen());
    }

    @Test
    void joinsPlaceholderReadsTheCachedProfile() {
        PlayerMock player = server.addPlayer("Alex");

        assertEquals("1", placeholders.resolve("joins", player));
        assertNull(placeholders.resolve("joins", null));
    }

    @Test
    void moduleIsSkippedWithoutADatabase() throws Exception {
        Path other = Files.createDirectory(directory.resolve("other"));
        ConfigService otherConfig = new ConfigService(other, NOPLogger.NOP_LOGGER);
        otherConfig.enable();
        Database stopped = new Database(other, otherConfig, NOPLogger.NOP_LOGGER);
        ProfileModule module = new ProfileModule(
                MockBukkit.createMockPlugin(), stopped, messages, placeholders, NOPLogger.NOP_LOGGER, clock);

        assertThrows(ModuleSkippedException.class, module::enable);
        assertFalse(stopped.isRunning());
    }

    private UUID store(PlayerProfile profile) throws Exception {
        database.call(connection -> {
            repository.save(connection, profile);
            return null;
        });
        return profile.id();
    }

    /** Waits for everything queued so far to reach the database. */
    private void flush() {
        assertTrue(profiles.awaitWrites(10));
    }

    private Optional<PlayerProfile> stored(UUID id) throws Exception {
        return database.call(connection -> repository.find(connection, id));
    }

    /** Keeps the single storage thread busy, so that what follows piles up behind it. */
    private CountDownLatch holdStorageThread() {
        CountDownLatch release = new CountDownLatch(1);
        database.submit(connection -> {
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return null;
        });
        return release;
    }

    private static final class TestClock extends Clock {

        private volatile long millis;

        TestClock(long millis) {
            this.millis = millis;
        }

        void set(long millis) {
            this.millis = millis;
        }

        @Override
        public long millis() {
            return millis;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }
    }
}
