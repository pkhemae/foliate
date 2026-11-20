package dev.foliate.modules.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.contracts.profile.ProfileSummary;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleSkippedException;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import dev.foliate.core.storage.Database;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
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
        ConfigService<PluginConfig> config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        messages = new MessageService(
                directory, getClass().getClassLoader()::getResourceAsStream, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);
        messages.enable();
        database = new Database(
                directory, () -> config.get().storage(), ProfileModule.MIGRATIONS, NOPLogger.NOP_LOGGER);
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

    @Test
    void checkPassesForAPlayerWhoseProfileWasSaved() {
        PlayerMock player = server.addPlayer("Alex");

        assertEquals(null, new ProfileCheck(profiles).problemWith(player, 1));
    }

    @Test
    void checkReportsAProfileThatIsMissingFromTheDatabase() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        flush();
        database.call(connection -> {
            connection.createStatement().executeUpdate("DELETE FROM foliate_profiles");
            return null;
        });

        assertEquals("the profile of Alex was not saved", new ProfileCheck(profiles).problemWith(player, 1));
    }

    @Test
    void checkAfterTheQuitReportsAProfileThatStayedInMemory() {
        PlayerMock player = server.addPlayer("Alex");

        assertEquals(
                "the profile of Alex stayed in memory after the player left",
                new ProfileCheck(profiles).problemAfterQuit(player.getUniqueId(), "Alex"));
        player.disconnect();
        assertEquals(null, new ProfileCheck(profiles).problemAfterQuit(player.getUniqueId(), "Alex"));
    }

    @Test
    void updateChangesTheCachedProfileAndSavesIt() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();

        PlayerProfile changed = profiles.update(id, profile -> withJoins(profile, profile.joins() + 10)).orElseThrow();
        flush();

        assertEquals(11, changed.joins());
        assertEquals(changed, profiles.find(id).orElseThrow());
        assertEquals(changed, stored(id).orElseThrow());
    }

    private static PlayerProfile withJoins(PlayerProfile profile, int joins) {
        return new PlayerProfile(profile.id(), profile.name(), profile.firstSeen(), profile.lastSeen(), joins);
    }

    @Test
    void checkReportsAPlayerWhoseReturnWasNotCounted() {
        PlayerMock player = server.addPlayer("Alex");

        assertEquals(
                "the profile of Alex counts 1 joins after visit 2", new ProfileCheck(profiles).problemWith(player, 2));
    }

    @Test
    void lookupGivesTheCachedProfileFieldForField() throws Exception {
        UUID id = store(new PlayerProfile(UUID.randomUUID(), "Alex", 10, 20, 4));
        clock.set(500);
        server.addPlayer(new PlayerMock(server, "Alex", id));

        ProfileSummary summary = profiles.lookup(id).orElseThrow();

        assertEquals(new ProfileSummary("Alex", 10, 500, 5), summary);
        assertTrue(profiles.lookup(UUID.randomUUID()).isEmpty());
    }

    @Test
    void checkReportsAProfileThatIsNotLoaded() {
        PlayerMock player = server.addPlayer("Alex");
        player.disconnect();

        assertEquals("the profile of Alex is not loaded", new ProfileCheck(profiles).problemWith(player, 1));
    }

    /** Letting a player in without their profile would mean guessing it: they are told why. */
    @Test
    void playerWhoseProfileCannotBeLoadedIsRefusedWithTheProfileMessage() throws Exception {
        database.disable();
        UUID id = UUID.randomUUID();
        AsyncPlayerPreLoginEvent login = new AsyncPlayerPreLoginEvent("Alex", InetAddress.getLoopbackAddress(), id, false);

        // An asynchronous event is called off the main thread, as the server does.
        java.util.concurrent.CompletableFuture.runAsync(() -> server.getPluginManager().callEvent(login)).get();

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, login.getLoginResult());
        assertEquals(messages.render(Locale.ROOT, "profile.load-failed"), login.kickMessage());
        assertTrue(plain(login.kickMessage()).contains("could not be loaded"));
    }

    private static String plain(net.kyori.adventure.text.Component component) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(component);
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
        ConfigService<PluginConfig> otherConfig = PluginConfig.service(other, NOPLogger.NOP_LOGGER);
        otherConfig.enable();
        Database stopped = new Database(
                other, () -> otherConfig.get().storage(), ProfileModule.MIGRATIONS, NOPLogger.NOP_LOGGER);
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
