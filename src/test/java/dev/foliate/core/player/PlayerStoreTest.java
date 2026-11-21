package dev.foliate.core.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.storage.Database;
import java.net.InetAddress;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.kyori.adventure.text.Component;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

/**
 * The machinery of per-player data, tested with a value of its own: it knows nothing of the
 * profile. Everything here is a guarantee that the profile, and any data built on the store, rely
 * on.
 */
class PlayerStoreTest {

    /** What the tests keep per player: how many times they joined. */
    record Visit(UUID id, String name, long firstSeen, long lastSeen, int visits) implements PlayerData {}

    private static final List<String> MIGRATIONS = List.of("001_create_visits.sql");

    @TempDir
    Path directory;

    private ServerMock server;
    private Database database;
    private PlayerStore<Visit> store;
    private final TestClock clock = new TestClock(1_000);
    private final VisitRepository repository = new VisitRepository();
    private final VisitLifecycle lifecycle = new VisitLifecycle();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock();
        ConfigService<PluginConfig> config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        database = new Database(directory, () -> config.get().storage(), MIGRATIONS, NOPLogger.NOP_LOGGER);
        database.enable();
        store = new PlayerStore<>(
                "visit",
                MockBukkit.createMockPlugin(),
                database,
                repository,
                lifecycle,
                () -> Component.text("could not load"),
                NOPLogger.NOP_LOGGER,
                clock);
        store.enable();
    }

    @AfterEach
    void tearDown() {
        store.disable();
        database.disable();
        MockBukkit.unmock();
    }

    @Test
    void firstJoinCreatesTheValueAndCountsTheJoin() {
        PlayerMock player = server.addPlayer("Alex");

        Visit visit = store.find(player.getUniqueId()).orElseThrow();
        assertEquals("Alex", visit.name());
        assertEquals(1, visit.visits());
        assertEquals(1_000, visit.firstSeen());
    }

    @Test
    void quitSavesTheValueAndEmptiesTheCache() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();

        clock.set(5_000);
        player.disconnect();
        flush();

        assertTrue(store.find(id).isEmpty());
        assertEquals(5_000, stored(id).orElseThrow().lastSeen());
    }

    @Test
    void shutdownSavesPlayersStillOnline() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();

        clock.set(5_000);
        store.disable();

        assertEquals(5_000, stored(id).orElseThrow().lastSeen());
    }

    @Test
    void returningPlayerKeepsCountingFromTheStoredValue() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "OldName", 10, 20, 4));

        server.addPlayer(new PlayerMock(server, "NewName", id));

        Visit visit = store.find(id).orElseThrow();
        assertEquals(5, visit.visits());
        assertEquals("NewName", visit.name());
        assertEquals(10, visit.firstSeen());
    }

    /** The server's order for a second login: pre-login of the new one, quit of the old, join. */
    @Test
    void loggingInAgainWhileOnlineKeepsTheValue() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        store.beginLogin(id, "Alex");
        store.join(id, "Alex");

        store.beginLogin(id, "Alex");
        store.quit(id);
        store.join(id, "Alex");
        flush();

        assertEquals(6, store.find(id).orElseThrow().visits());
        assertEquals(6, stored(id).orElseThrow().visits());
    }

    /**
     * A second login while online copies the session's value at pre-login; a change made after that
     * and before the old session is kicked must reach the new session, and the database.
     */
    @Test
    void changeMadeBetweenASecondLoginAndTheKickIsKept() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        store.beginLogin(id, "Alex");
        store.join(id, "Alex"); // 5

        store.beginLogin(id, "Alex"); // a second client logs in: the session's value is copied
        store.update(id, visit -> withVisits(visit, visit.visits() + 100)); // 105, on the old session
        store.quit(id); // the old session is kicked and saves 105
        store.join(id, "Alex"); // the new session
        flush();

        assertEquals(106, store.find(id).orElseThrow().visits());
        assertEquals(106, stored(id).orElseThrow().visits());
    }

    /**
     * The server disconnects the old session before the new one joins. Were the new one to join
     * first, it would still start from the old session's value, not from the older copy taken at
     * pre-login.
     */
    @Test
    void joinWhileTheOldSessionIsStillOnlineStartsFromItsValue() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        store.beginLogin(id, "Alex");
        store.join(id, "Alex"); // 5

        store.beginLogin(id, "Alex"); // copies 5
        store.update(id, visit -> withVisits(visit, visit.visits() + 100)); // 105
        store.join(id, "Alex");
        flush();

        assertEquals(106, store.find(id).orElseThrow().visits());
        assertEquals(106, stored(id).orElseThrow().visits());
    }

    /** A login must read behind the saves still queued for the player, not ahead of them. */
    @Test
    void loginWhileEarlierSavesAreStillQueuedSeesThem() throws Exception {
        UUID id = UUID.randomUUID();
        store.beginLogin(id, "Alex");
        CountDownLatch release = holdStorageThread();
        store.join(id, "Alex");
        store.quit(id);

        CompletableFuture<Void> secondLogin = CompletableFuture.runAsync(() -> {
            try {
                store.beginLogin(id, "Alex");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        release.countDown();
        secondLogin.get(10, TimeUnit.SECONDS);
        store.join(id, "Alex");
        flush();

        assertEquals(2, stored(id).orElseThrow().visits());
    }

    @Test
    void loginThatNeverBecomesAJoinSavesNothing() throws Exception {
        UUID id = UUID.randomUUID();

        store.beginLogin(id, "Banned");
        store.disable();

        assertTrue(stored(id).isEmpty());
    }

    /** A connection can end after pre-login without a trace: such entries expire by themselves. */
    @Test
    void loginsThatNeverBecameAJoinAreForgottenAfterAWhile() throws Exception {
        store.beginLogin(UUID.randomUUID(), "Banned");
        store.beginLogin(UUID.randomUUID(), "Full");
        assertEquals(2, store.pendingLogins());

        clock.set(1_000 + PlayerStore.LOGIN_TTL_MILLIS + 1);
        store.beginLogin(UUID.randomUUID(), "Next");

        assertEquals(1, store.pendingLogins());
    }

    @Test
    void loginThatWaitedTooLongIsNotTrustedAtJoinButTheValueIsStillLoaded() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        store.beginLogin(id, "Alex");
        // The row changes while the login waits: only a fresh load can see it.
        put(new Visit(id, "Alex", 10, 20, 9));

        clock.set(1_000 + PlayerStore.LOGIN_TTL_MILLIS + 1);
        store.join(id, "Alex");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.find(id).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(10, store.find(id).orElseThrow().visits());
        assertEquals(0, store.pendingLogins());
    }

    @Test
    void joinWithoutAPreLoginWaitsForTheStoredValueInsteadOfResettingIt() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));

        store.join(id, "Alex");
        flush();

        Visit visit = stored(id).orElseThrow();
        assertEquals(5, visit.visits());
        assertEquals(10, visit.firstSeen());
    }

    @Test
    void savesOfOnePlayerLandInTheOrderTheyWereMade() throws Exception {
        UUID id = UUID.randomUUID();

        for (int session = 0; session < 25; session++) {
            clock.set(1_000 + session);
            store.beginLogin(id, "Alex");
            store.join(id, "Alex");
            store.quit(id);
        }
        flush();

        Visit visit = stored(id).orElseThrow();
        assertEquals(25, visit.visits());
        assertEquals(1_024, visit.lastSeen());
    }

    /** The map of queues used to keep one entry per player ever seen. */
    @Test
    void queuesAreReleasedOnceTheirWritesLanded() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        player.disconnect();
        flush();

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (store.pendingQueues() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, store.pendingQueues());
    }

    /** A value loaded after the player quit must not be cached for a player who is gone. */
    @Test
    void valueLoadedAfterTheQuitIsNotCached() throws Exception {
        UUID id = UUID.randomUUID();
        server.addPlayer(new PlayerMock(server, "Alex", id));
        store.quit(id);
        flush();
        CountDownLatch release = holdStorageThread();

        store.join(id, "Alex"); // no pre-login: the load happens in the background
        clock.set(9_000);
        store.quit(id); // and the player quits before it is done
        release.countDown();
        // The callback that caches the value also saves it, counting the second join: waiting for
        // the writes, as the shutdown does, must wait for that save too.
        flush();
        assertEquals(2, stored(id).orElseThrow().visits(), "the load callback's save was not awaited");
        assertEquals(9_000, stored(id).orElseThrow().lastSeen(), "the quit was not applied");

        assertTrue(store.find(id).isEmpty());
    }

    /**
     * The save a callback queues is queued behind the work already there: waiting for the writes
     * must wait for it too, and not return once the read that led to it is done.
     */
    @Test
    void awaitingTheWritesWaitsForTheSaveOfALateLoadedValue() throws Exception {
        UUID id = UUID.randomUUID();
        CountDownLatch releaseRead = holdStorageThread();
        store.join(id, "Alex"); // no pre-login: the read is queued behind the hold
        CountDownLatch releaseSave = holdStorageThread(); // and this one behind the read
        CompletableFuture<Boolean> awaited = CompletableFuture.supplyAsync(() -> store.awaitWrites(10));

        releaseRead.countDown(); // the read completes, its callback queues the save behind the second hold

        assertThrows(TimeoutException.class, () -> awaited.get(1, TimeUnit.SECONDS));
        releaseSave.countDown();
        assertTrue(awaited.get(10, TimeUnit.SECONDS));
        assertEquals(1, stored(id).orElseThrow().visits());
    }

    @Test
    void updateChangesTheCachedValueAndSavesIt() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();

        Visit changed = store.update(id, visit -> withVisits(visit, visit.visits() + 10)).orElseThrow();
        flush();

        assertEquals(11, changed.visits());
        assertEquals(changed, store.find(id).orElseThrow());
        assertEquals(changed, stored(id).orElseThrow());
    }

    @Test
    void changesMadeFromManyThreadsAtOnceAreAllKeptAndSavedInOrder() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        int threads = 8;
        int each = 200;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> done = new ArrayList<>();
            for (int thread = 0; thread < threads; thread++) {
                done.add(pool.submit(() -> {
                    for (int i = 0; i < each; i++) {
                        store.update(id, visit -> withVisits(visit, visit.visits() + 1));
                    }
                }));
            }
            for (Future<?> future : done) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        flush();

        assertEquals(1 + threads * each, store.find(id).orElseThrow().visits());
        // The last save to land is the last change made, whatever the number of storage threads.
        assertEquals(1 + threads * each, stored(id).orElseThrow().visits());
    }

    @Test
    void changeThatThrowsOrReturnsAnotherPlayersValueChangesNothing() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        Visit before = store.find(id).orElseThrow();

        assertThrows(IllegalStateException.class, () -> store.update(id, visit -> {
            throw new IllegalStateException("broken change");
        }));
        assertThrows(IllegalArgumentException.class, () -> store.update(id, visit ->
                new Visit(UUID.randomUUID(), visit.name(), visit.firstSeen(), visit.lastSeen(), 99)));
        assertThrows(NullPointerException.class, () -> store.update(id, visit -> null));
        flush();

        assertEquals(before, store.find(id).orElseThrow());
        // The lock was let go: the next change works.
        assertEquals(
                before.visits() + 1,
                store.update(id, visit -> withVisits(visit, visit.visits() + 1)).orElseThrow().visits());
    }

    @Test
    void changeMadeJustAfterTheLoginIsNotOverwrittenByTheJoinsOwnSave() throws Exception {
        UUID id = UUID.randomUUID();
        store.beginLogin(id, "Alex");

        store.join(id, "Alex");
        store.update(id, visit -> withVisits(visit, 40));
        flush();

        assertEquals(40, stored(id).orElseThrow().visits());
    }

    /**
     * Changes made while a save waits join it: a burst is one write of the latest value, and a
     * login that follows does not wait behind thousands of them.
     */
    @Test
    void aBurstOfChangesIsOneWriteOfTheLatestValue() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        int before = repository.saves.get();
        CountDownLatch release = holdStorageThread();

        for (int i = 0; i < 10_000; i++) {
            store.update(id, visit -> withVisits(visit, visit.visits() + 1));
        }
        clock.set(5_000);
        store.quit(id);
        release.countDown();
        flush();

        assertEquals(1, repository.saves.get() - before, "the changes were not written together");
        Visit stored = stored(id).orElseThrow();
        assertEquals(10_001, stored.visits());
        assertEquals(5_000, stored.lastSeen(), "the quit was not part of the write");
    }

    /** A save that could not run must not keep the changes made after it from being saved. */
    @Test
    void aChangeAfterAFailedSaveIsStillSaved() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        database.disable();
        store.update(id, visit -> withVisits(visit, 50)); // its save fails: the database is stopped
        database.enable();

        store.update(id, visit -> withVisits(visit, 60));
        flush();

        assertEquals(60, stored(id).orElseThrow().visits());
    }

    @Test
    void updateOfAPlayerWhoIsNotOnlineChangesNothing() throws Exception {
        UUID id = UUID.randomUUID();

        assertTrue(store.update(id, visit -> withVisits(visit, 99)).isEmpty());
        flush();

        assertTrue(stored(id).isEmpty());
    }

    @Test
    void checksSayWhatIsWrongForAConnectedPlayer() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        assertEquals(null, PlayerStoreChecks.problemWith(store, player));

        flush();
        database.call(connection -> {
            connection.createStatement().executeUpdate("DELETE FROM test_visits");
            return null;
        });
        assertEquals("the visit of Alex was not saved", PlayerStoreChecks.problemWith(store, player));

        store.quit(player.getUniqueId());
        assertEquals("the visit of Alex is not loaded", PlayerStoreChecks.problemWith(store, player));
    }

    @Test
    void checkAfterTheQuitReportsAValueThatStayedInMemory() {
        PlayerMock player = server.addPlayer("Alex");

        assertEquals(
                "the visit of Alex stayed in memory after the player left",
                PlayerStoreChecks.problemAfterQuit(store, player.getUniqueId(), "Alex"));
        store.quit(player.getUniqueId());
        assertEquals(null, PlayerStoreChecks.problemAfterQuit(store, player.getUniqueId(), "Alex"));
    }

    @Test
    void playerWhoseDataCannotBeLoadedIsRefusedWithTheMessageWeGave() throws Exception {
        // The database is gone: letting the player in would mean guessing what is stored.
        database.disable();
        UUID id = UUID.randomUUID();
        AsyncPlayerPreLoginEvent login =
                new AsyncPlayerPreLoginEvent("Alex", InetAddress.getLoopbackAddress(), id, false);

        store.onPreLogin(login);

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, login.getLoginResult());
        assertEquals(Component.text("could not load"), login.kickMessage());
    }

    /** An interrupted load has not loaded the data either: the player is refused all the same. */
    @Test
    void playerWhoseLoadIsInterruptedIsRefused() {
        AsyncPlayerPreLoginEvent login =
                new AsyncPlayerPreLoginEvent("Alex", InetAddress.getLoopbackAddress(), UUID.randomUUID(), false);

        // Held, so that the load is still running when it is waited for: an interrupted wait
        // only fails for a result that is not there yet.
        CountDownLatch release = holdStorageThread();
        Thread.currentThread().interrupt();
        try {
            store.onPreLogin(login);
        } finally {
            assertTrue(Thread.interrupted(), "the interruption was not kept");
            release.countDown();
        }

        assertEquals(AsyncPlayerPreLoginEvent.Result.KICK_OTHER, login.getLoginResult());
    }

    /** Once disabled, the store caches and queues nothing: the database is about to close. */
    @Test
    void joinAfterTheStoreIsDisabledDoesNothing() throws Exception {
        UUID id = UUID.randomUUID();
        store.beginLogin(id, "Alex");
        store.disable();

        store.join(id, "Alex");
        store.beginLogin(id, "Alex");
        flush();

        assertTrue(store.find(id).isEmpty());
        assertEquals(0, store.pendingLogins());
        // A join that went on would have loaded the value in the background and saved it. (The
        // number of queues is no proof: the last one is dropped just after its work completes.)
        assertTrue(stored(id).isEmpty(), "the join queued work after the store was disabled");
    }

    /**
     * A load still running when the store is disabled completes while the shutdown waits for the
     * writes: its value is saved like those of the other players still online, and not cached.
     */
    @Test
    void lateLoadThatCompletesDuringTheShutdownIsSavedNotCached() throws Exception {
        UUID id = UUID.randomUUID();
        CountDownLatch release = holdStorageThread();
        store.join(id, "Alex"); // no pre-login: the load waits behind the hold
        clock.set(9_000);

        CompletableFuture<Void> disabling = CompletableFuture.runAsync(store::disable);
        while (store.enabled()) {
            Thread.onSpinWait();
        }
        release.countDown(); // the store is off: the load completes while disable() waits for it
        disabling.get(20, TimeUnit.SECONDS);

        assertTrue(store.find(id).isEmpty());
        assertEquals(9_000, stored(id).orElseThrow().lastSeen());
    }

    /**
     * A load that a quit and a new join overtook belongs to the first session: it is saved as of
     * that quit, and only the new load is cached.
     */
    @Test
    void lateLoadOvertakenByAQuitAndANewJoinIsSavedAsOfTheQuit() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        CountDownLatch release = holdStorageThread();
        store.join(id, "Alex"); // no pre-login: the load waits behind the hold
        clock.set(9_000);
        store.quit(id);
        clock.set(12_000);
        store.join(id, "Alex"); // a second load, behind the first

        release.countDown();
        flush();

        assertTrue(store.find(id).isPresent(), "the second session has no value");
        assertEquals(12_000, store.find(id).orElseThrow().lastSeen());
    }

    @Test
    void lifecycleThatReturnsAnotherPlayersValueAtJoinKeepsTheValueAsItWas() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        UUID other = UUID.randomUUID();
        lifecycle.joinedValue = visit -> new Visit(other, visit.name(), visit.firstSeen(), visit.lastSeen(), 99);

        store.beginLogin(id, "Alex");
        store.join(id, "Alex");
        flush();

        assertEquals(4, store.find(id).orElseThrow().visits());
        assertTrue(stored(other).isEmpty(), "a row was written for the other player");
    }

    @Test
    void lifecycleThatThrowsAtQuitStillSavesThePlayerAndDoesNotStopTheShutdown() throws Exception {
        PlayerMock broken = server.addPlayer("Broken");
        PlayerMock fine = server.addPlayer("Fine");
        lifecycle.leftFails = broken.getUniqueId();
        clock.set(9_000);

        store.disable();

        assertEquals(1_000, stored(broken.getUniqueId()).orElseThrow().lastSeen(), "saved as it was");
        assertEquals(9_000, stored(fine.getUniqueId()).orElseThrow().lastSeen(), "the others were still saved");
        assertTrue(store.find(broken.getUniqueId()).isEmpty());
    }

    @Test
    void aStoreCannotBeEnabledTwice() {
        assertThrows(IllegalStateException.class, store::enable);
    }

    /**
     * The join, the quit and the late load take the lock that `update` holds while it computes and
     * queues its save: that is what makes the saves reach the database in the order of the changes.
     * A change in progress holds the lock, and each of them must wait for it.
     */
    @Test
    void joinQuitAndLateLoadWaitForAChangeInProgress() throws Exception {
        UUID busy = server.addPlayer("Busy").getUniqueId();
        UUID loggingIn = UUID.randomUUID();
        store.beginLogin(loggingIn, "Login");
        UUID leaving = server.addPlayer("Leaving").getUniqueId();
        UUID lateJoin = UUID.randomUUID();

        CountDownLatch inChange = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread change = new Thread(() -> store.update(busy, visit -> {
            inChange.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return visit;
        }));
        change.start();
        assertTrue(inChange.await(10, TimeUnit.SECONDS));

        ExecutorService others = Executors.newFixedThreadPool(3);
        try {
            Future<?> join = others.submit(() -> store.join(loggingIn, "Login"));
            Future<?> quit = others.submit(() -> store.quit(leaving));
            Future<?> late = others.submit(() -> store.join(lateJoin, "Late"));
            Thread.sleep(500);
            assertTrue(store.find(loggingIn).isEmpty(), "the join did not wait for the change");
            assertTrue(store.find(leaving).isPresent(), "the quit did not wait for the change");
            // The late join's load is done by now, but its value is cached under the lock too.
            assertTrue(store.find(lateJoin).isEmpty(), "the late load did not wait for the change");

            release.countDown();
            change.join();
            join.get(10, TimeUnit.SECONDS);
            quit.get(10, TimeUnit.SECONDS);
            late.get(10, TimeUnit.SECONDS);
        } finally {
            others.shutdownNow();
        }
        flush();

        assertTrue(store.find(loggingIn).isPresent());
        assertTrue(store.find(leaving).isEmpty());
        assertTrue(store.find(lateJoin).isPresent());
    }

    private static Visit withVisits(Visit visit, int visits) {
        return new Visit(visit.id(), visit.name(), visit.firstSeen(), visit.lastSeen(), visits);
    }

    private UUID put(Visit visit) throws Exception {
        database.call(connection -> {
            repository.save(connection, visit);
            return null;
        });
        return visit.id();
    }

    private Optional<Visit> stored(UUID id) throws Exception {
        return database.call(connection -> repository.find(connection, id));
    }

    /** Waits for everything queued so far to reach the database. */
    private void flush() {
        assertTrue(store.awaitWrites(10));
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

    private static final class VisitLifecycle implements PlayerLifecycle<Visit> {

        /** Replaces what a join makes, to play a lifecycle that gets it wrong. */
        volatile java.util.function.UnaryOperator<Visit> joinedValue = visit -> visit;

        /** A player whose quit makes the lifecycle throw. */
        volatile UUID leftFails;

        @Override
        public Visit create(UUID id, String name, long now) {
            return new Visit(id, name, now, now, 0);
        }

        @Override
        public Visit joined(Visit stored, String name, long now) {
            return joinedValue.apply(new Visit(stored.id(), name, stored.firstSeen(), now, stored.visits() + 1));
        }

        @Override
        public Visit left(Visit session, long now) {
            if (session.id().equals(leftFails)) {
                throw new IllegalStateException("broken lifecycle");
            }
            return new Visit(session.id(), session.name(), session.firstSeen(), now, session.visits());
        }
    }

    private static final class VisitRepository implements PlayerRepository<Visit> {

        /** How many rows were written, to tell a coalesced save from one per change. */
        final java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        public Optional<Visit> find(Connection connection, UUID id) throws SQLException {
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT name, first_seen, last_seen, visits FROM test_visits WHERE id = ?")) {
                select.setString(1, id.toString());
                try (ResultSet row = select.executeQuery()) {
                    return row.next()
                            ? Optional.of(new Visit(
                                    id, row.getString("name"), row.getLong("first_seen"), row.getLong("last_seen"), row.getInt("visits")))
                            : Optional.empty();
                }
            }
        }

        @Override
        public void save(Connection connection, Visit visit) throws SQLException {
            saves.incrementAndGet();
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE test_visits SET name = ?, last_seen = ?, visits = ? WHERE id = ?")) {
                update.setString(1, visit.name());
                update.setLong(2, visit.lastSeen());
                update.setInt(3, visit.visits());
                update.setString(4, visit.id().toString());
                if (update.executeUpdate() > 0) {
                    return;
                }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO test_visits (id, name, first_seen, last_seen, visits) VALUES (?, ?, ?, ?, ?)")) {
                insert.setString(1, visit.id().toString());
                insert.setString(2, visit.name());
                insert.setLong(3, visit.firstSeen());
                insert.setLong(4, visit.lastSeen());
                insert.setInt(5, visit.visits());
                insert.executeUpdate();
            }
        }
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
