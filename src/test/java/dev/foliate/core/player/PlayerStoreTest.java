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
import dev.foliate.testing.RecordingLogger;
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
    private final RecordingLogger logger = new RecordingLogger();

    /** Retries waiting to run, when a test runs them by hand. */
    private final List<Runnable> pendingRetries = new java.util.concurrent.CopyOnWriteArrayList<>();

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
                logger,
                clock);
        // Retries at once: the tests do not wait for real delays.
        store.retryDelays(millis -> Runnable::run);
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

        // The save waited for the storage thread: every change made meanwhile joined it.
        assertEquals(1, repository.saves.get() - before, "the changes were not written together");
        Visit stored = stored(id).orElseThrow();
        assertEquals(10_001, stored.visits());
        assertEquals(5_000, stored.lastSeen(), "the quit was not part of the write");
    }

    /** A change made while a save is running is not part of it: it is saved after it. */
    @Test
    void aChangeMadeWhileASaveRunsIsSavedAfterIt() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        CountDownLatch inSave = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.duringSave = () -> {
            inSave.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        int before = repository.saves.get();

        store.update(id, visit -> withVisits(visit, 50));
        assertTrue(inSave.await(10, TimeUnit.SECONDS));
        store.update(id, visit -> withVisits(visit, 60));
        repository.duringSave = () -> {};
        release.countDown();
        flush();

        assertEquals(2, repository.saves.get() - before);
        assertEquals(60, stored(id).orElseThrow().visits());
    }

    /**
     * A save waiting for a storage thread has not started: a change made meanwhile joins it. At a
     * shutdown with a slow database, the quit's save would otherwise queue behind it, one write
     * more per player.
     */
    @Test
    void aSaveWaitingForTheDatabaseTakesTheChangesMadeMeanwhile() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        int before = repository.saves.get();
        CountDownLatch release = holdStorageThread();

        store.update(id, visit -> withVisits(visit, 50)); // leaves the queue, waits for the thread
        store.update(id, visit -> withVisits(visit, 60));
        release.countDown();
        flush();

        assertEquals(1, repository.saves.get() - before, "the second change did not join the waiting save");
        assertEquals(60, stored(id).orElseThrow().visits());
    }

    /** A save that fails for a reason that may pass is tried again, with the latest value. */
    @Test
    void aFailedSaveIsRetriedWithTheLatestValue() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        CountDownLatch failed = new CountDownLatch(1);
        repository.failSaves(2, failed, null, true);

        store.update(id, visit -> withVisits(visit, 50));
        assertTrue(failed.await(10, TimeUnit.SECONDS));
        store.update(id, visit -> withVisits(visit, 60)); // made while the save waits for its retry
        flush();

        assertEquals(60, stored(id).orElseThrow().visits());
        assertEquals(0, store.lostCount());
    }

    /** The quit's save during an outage is kept until the database answers again. */
    @Test
    void aQuitSavedDuringAnOutageReachesTheDatabaseOnceItIsBack() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        repository.failSaves(3, new CountDownLatch(3), null, true);

        clock.set(7_000);
        store.quit(id);
        flush();

        assertEquals(7_000, stored(id).orElseThrow().lastSeen());
    }

    /**
     * A save that failed after it started, while a newer change queued a save of its own behind
     * it, is not retried: the newer save holds the whole newer value.
     */
    @Test
    void aFailedSaveOvertakenByANewerOneIsNotWrittenAgain() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        CountDownLatch inSave = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        repository.duringSave = () -> {
            inSave.countDown();
            try {
                release.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        repository.failSaves(1, new CountDownLatch(1), null, true);
        int before = repository.saves.get();

        store.update(id, visit -> withVisits(visit, 50)); // starts, then fails
        assertTrue(inSave.await(10, TimeUnit.SECONDS));
        store.update(id, visit -> withVisits(visit, 60)); // a save of its own, behind it
        repository.duringSave = () -> {};
        release.countDown();
        flush();

        assertEquals(1, repository.saves.get() - before, "the failed save was written again");
        assertEquals(60, stored(id).orElseThrow().visits());
    }

    /** A login read goes ahead of the saves of other players: someone waits for it. */
    @Test
    void aLoginReadGoesAheadOfTheOtherPlayersSaves() throws Exception {
        List<UUID> players = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            players.add(server.addPlayer("Player" + i).getUniqueId());
        }
        flush();
        CountDownLatch release = holdStorageThread();
        for (UUID player : players) {
            store.update(player, visit -> withVisits(visit, 99));
        }
        int before = repository.saves.get();

        CompletableFuture<Void> login = CompletableFuture.runAsync(() -> {
            try {
                store.beginLogin(UUID.randomUUID(), "Newcomer");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        // The read is queued once beginLogin waits on it: give it the time to get there.
        Thread.sleep(200);
        release.countDown();
        login.get(10, TimeUnit.SECONDS);
        flush();

        assertEquals(before, repository.savesAtLastFind, "the login read waited behind other players' saves");
    }

    /**
     * Two logins of one player that overlap: the first session joins, plays and quits while the
     * second login's read waits. That read was queued before the quit's save, so it sees the value
     * from before the first session: the second session must start from what the quit saved.
     */
    @Test
    void aSessionThatQuitDuringASecondLoginsReadIsNotLost() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        store.beginLogin(id, "Alex"); // the first login
        CountDownLatch release = holdStorageThread();
        CompletableFuture<Void> secondLogin = CompletableFuture.runAsync(() -> {
            try {
                store.beginLogin(id, "Alex"); // not online yet: reads, behind the hold
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        Thread.sleep(200);

        store.join(id, "Alex"); // 5, from the first login
        store.update(id, visit -> withVisits(visit, visit.visits() + 100)); // 105
        store.quit(id); // saves 105, queued behind the second login's read
        release.countDown();
        secondLogin.get(10, TimeUnit.SECONDS);
        store.join(id, "Alex");
        flush();

        assertEquals(106, store.find(id).orElseThrow().visits());
        assertEquals(106, stored(id).orElseThrow().visits());
    }

    /** A load after a join without pre-login that fails is tried again while the player is online. */
    @Test
    void aFailedLateLoadIsRetried() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        repository.failFinds.set(2);

        store.join(id, "Alex");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (store.find(id).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(5, store.find(id).orElseThrow().visits());
    }

    /**
     * A save that can never succeed (here a broken constraint, which SQLite reports like any other
     * error) is tried a few times, then given up and logged: the player's queue moves on, and
     * their next login is not refused.
     */
    @Test
    void aSaveThatCanNeverSucceedIsGivenUpAndThePlayerCanStillLogIn() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        repository.failSaves(Integer.MAX_VALUE, new CountDownLatch(0), null, false);
        int before = repository.attempts.get();

        store.update(id, visit -> withVisits(visit, 50));
        flush();
        repository.failSaves(0, new CountDownLatch(0), null, true);
        CompletableFuture<Void> relogin = CompletableFuture.runAsync(() -> {
            try {
                store.quit(id);
                store.beginLogin(id, "Alex");
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
        relogin.get(5, TimeUnit.SECONDS);
        flush(); // the login may start from the quit's save before it lands

        assertEquals(1 + PlayerStore.UNCERTAIN_RETRIES, repository.attempts.get() - before - 1, "not given up after a few tries");
        assertEquals(1, logger.lines().stream().filter(line -> line.startsWith("ERROR Could not save")).count(), logger.lines().toString());
        assertEquals(0, store.lostCount(), "a save given up for its own error is not a shutdown loss");
    }

    /**
     * A late load that failed and is retried after the player quit and came back must not save a
     * value read then over what the new session saved.
     */
    @Test
    void aLateLoadRetriedAfterTheQuitDoesNotOverwriteTheNextSession() throws Exception {
        store.retryDelays(millis -> pendingRetries::add);
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        repository.failFinds.set(1);

        store.join(id, "Alex"); // no pre-login: the load fails, its retry waits
        flush();
        assertEquals(1, pendingRetries.size());
        store.quit(id);
        store.beginLogin(id, "Alex");
        store.join(id, "Alex"); // 5
        store.update(id, visit -> withVisits(visit, 50));
        flush();
        pendingRetries.forEach(Runnable::run);
        flush();

        assertEquals(50, store.find(id).orElseThrow().visits());
        assertEquals(50, stored(id).orElseThrow().visits());
    }

    /** A player counted as lost while the database was stopped is not reported once saved again. */
    @Test
    void aPlayerSavedAfterALostSaveIsNotReportedAtShutdown() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        database.disable();
        store.update(id, visit -> withVisits(visit, 50));
        database.enable();
        assertEquals(1, store.lostCount());

        store.update(id, visit -> withVisits(visit, 60));
        flush();

        assertEquals(0, store.lostCount());
    }

    /**
     * An outage is logged when the first player's saves fail and when the last one's land: one
     * player stuck while the others save is one warning, not one per save of someone else.
     */
    @Test
    void anOutageIsLoggedOnceWhileOtherPlayersStillSave() throws Exception {
        store.retryDelays(millis -> pendingRetries::add);
        UUID stuck = server.addPlayer("Stuck").getUniqueId();
        UUID other = server.addPlayer("Other").getUniqueId();
        flush();
        repository.failSaves(1, new CountDownLatch(1), stuck, true);

        store.update(stuck, visit -> withVisits(visit, 50)); // fails, its retry waits
        // Not flush(): the stuck player's queue waits for its retry, which the test runs below.
        waitUntil(() -> pendingRetries.size() == 1);
        for (int i = 1; i <= 5; i++) {
            int visits = 100 + i;
            store.update(other, visit -> withVisits(visit, visits));
            waitUntil(() -> stored(other).orElseThrow().visits() == visits);
        }
        List<Runnable> retries = List.copyOf(pendingRetries);
        pendingRetries.clear();
        retries.forEach(Runnable::run);
        flush();

        List<String> outage = logger.lines().stream()
                .filter(line -> line.contains("retrying") || line.contains("saved again"))
                .toList();
        assertEquals(2, outage.size(), outage.toString());
        assertTrue(outage.get(0).startsWith("WARN"), outage.toString());
        assertTrue(outage.get(1).startsWith("INFO"), outage.toString());
        assertEquals(50, stored(stuck).orElseThrow().visits());
    }

    /**
     * A player who quits and logs in again while their quit's save waits for a storage thread
     * starts from that save's value, without waiting behind every other player's saves.
     */
    @Test
    void aLoginWhileTheQuitsSaveWaitsStartsFromItWithoutWaiting() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        store.update(id, visit -> withVisits(visit, 50));
        flush();
        CountDownLatch release = holdStorageThread();
        try {
            clock.set(7_000);
            store.quit(id); // its save waits behind the hold

            CompletableFuture<Void> login = CompletableFuture.runAsync(() -> {
                try {
                    store.beginLogin(id, "Alex");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            });
            login.get(2, TimeUnit.SECONDS);
            store.join(id, "Alex");
        } finally {
            release.countDown();
        }
        flush();

        Visit visit = store.find(id).orElseThrow();
        assertEquals(51, visit.visits());
        assertEquals(51, stored(id).orElseThrow().visits());
    }

    /** While the database is down, a player whose save is being retried can still log in. */
    @Test
    void aPlayerWhoseSaveIsRetriedCanStillLogIn() throws Exception {
        store.retryDelays(millis -> pendingRetries::add);
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        repository.failSaves(1, new CountDownLatch(1), id, true);

        store.update(id, visit -> withVisits(visit, 50));
        store.quit(id); // joins the save, which fails and waits for its retry
        waitUntil(() -> pendingRetries.size() == 1);
        store.beginLogin(id, "Alex"); // would wait for the retry, then time out
        store.join(id, "Alex");
        List<Runnable> retries = List.copyOf(pendingRetries);
        pendingRetries.clear();
        retries.forEach(Runnable::run);
        flush();

        assertEquals(51, store.find(id).orElseThrow().visits());
        assertEquals(51, stored(id).orElseThrow().visits());
    }

    /**
     * When the shutdown stops waiting, the saves still running get the database's own grace: the
     * report waits for their outcome instead of counting them as lost beforehand.
     */
    @Test
    void theShutdownReportWaitsForTheSavesTheDatabaseStillFinishes() throws Exception {
        PlayerMock player = server.addPlayer("Alex");
        UUID id = player.getUniqueId();
        flush();
        store.flushTimeoutSeconds(1);
        CountDownLatch release = holdStorageThread();
        store.update(id, visit -> withVisits(visit, 50));

        store.disable(); // gives up waiting after a second: the save is still queued
        assertTrue(logger.lines().stream().noneMatch(line -> line.contains("could not be saved")), logger.lines().toString());
        release.countDown();
        database.disable(); // its grace lets the save land

        assertTrue(logger.lines().stream().noneMatch(line -> line.contains("could not be saved")), logger.lines().toString());
        database.enable();
        assertEquals(50, stored(id).orElseThrow().visits());
    }

    /** What could not be saved is reported in one line when the store stops, not one per player. */
    @Test
    void savesGivenUpAreReportedOnceAtShutdown() throws Exception {
        for (int i = 0; i < 3; i++) {
            UUID id = UUID.randomUUID();
            store.beginLogin(id, "Player" + i);
            store.join(id, "Player" + i);
        }
        flush();
        database.disable(); // the saves at shutdown cannot run

        store.disable();
        database.enable();

        List<String> warnings = logger.lines().stream().filter(line -> line.startsWith("WARN")).toList();
        assertEquals(1, warnings.size(), warnings.toString());
        assertTrue(warnings.getFirst().contains("of 3 players"), warnings.getFirst());
        assertTrue(logger.lines().stream().noneMatch(line -> line.startsWith("ERROR")), logger.lines().toString());
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

    /** Enabled while they play (Paper's /reload): no join will come for them, so they are loaded at once. */
    @Test
    void playersAlreadyOnlineWhenTheStoreStartsAreLoaded() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        store.disable();
        server.addPlayer(new PlayerMock(server, "Alex", id));
        store = new PlayerStore<>(
                "visit", MockBukkit.createMockPlugin(), database, repository, lifecycle,
                () -> Component.text("could not load"), NOPLogger.NOP_LOGGER, clock);

        store.enable();
        flush();

        assertEquals(5, store.find(id).orElseThrow().visits());
    }

    /** Seen by the store's start and again by its join event: one join, not two. */
    @Test
    void aPlayerJoiningAsTheStoreStartsIsCountedOnce() throws Exception {
        UUID id = put(new Visit(UUID.randomUUID(), "Alex", 10, 20, 4));
        store.disable();
        server.addPlayer(new PlayerMock(server, "Alex", id));
        store = new PlayerStore<>(
                "visit", MockBukkit.createMockPlugin(), database, repository, lifecycle,
                () -> Component.text("could not load"), NOPLogger.NOP_LOGGER, clock);
        store.enable();

        store.join(id, "Alex"); // the join event of the same player, while the load runs
        flush();
        store.join(id, "Alex"); // or after it
        flush();

        assertEquals(5, store.find(id).orElseThrow().visits());
        assertEquals(5, stored(id).orElseThrow().visits());
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

    /** Polls a condition for up to ten seconds. */
    private static void waitUntil(java.util.concurrent.Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.call()) {
            assertTrue(System.nanoTime() < deadline, "the condition never held");
            Thread.sleep(5);
        }
    }

    /** Waits for everything queued so far to reach the database. */
    private void flush() {
        assertTrue(store.awaitWrites(10));
    }

    /** Keeps the single storage thread busy, so that what follows piles up behind it. */
    private CountDownLatch holdStorageThread() {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<?> _ = database.submit(connection -> {
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

        /** Runs inside each save, to hold one while it is running. */
        volatile Runnable duringSave = () -> {};

        /** How many rows were written, to tell a coalesced save from one per change. */
        final java.util.concurrent.atomic.AtomicInteger saves = new java.util.concurrent.atomic.AtomicInteger();

        /** How many rows were written when the last read began. */
        volatile int savesAtLastFind;

        /** How many saves were tried, failed ones included. */
        final java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();

        /** How many of the next saves fail, and a latch counting them. */
        private final java.util.concurrent.atomic.AtomicInteger failSaves = new java.util.concurrent.atomic.AtomicInteger();

        private volatile CountDownLatch saveFailures = new CountDownLatch(0);

        /** The only player whose saves fail, or null for all. */
        private volatile @org.jspecify.annotations.Nullable UUID failingPlayer;

        /** Whether the failures are those of an unreachable database, or an error that says nothing. */
        private volatile boolean unreachable = true;

        /** How many of the next reads fail as an unreachable database would. */
        final java.util.concurrent.atomic.AtomicInteger failFinds = new java.util.concurrent.atomic.AtomicInteger();

        void failSaves(int count, CountDownLatch failures, @org.jspecify.annotations.Nullable UUID player, boolean unreachable) {
            saveFailures = failures;
            failingPlayer = player;
            this.unreachable = unreachable;
            failSaves.set(count);
        }

        @Override
        public Optional<Visit> find(Connection connection, UUID id) throws SQLException {
            savesAtLastFind = saves.get();
            if (failFinds.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new SQLException("Communications link failure", "08S01");
            }
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
            duringSave.run();
            attempts.incrementAndGet();
            UUID only = failingPlayer;
            if ((only == null || only.equals(visit.id())) && failSaves.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                saveFailures.countDown();
                throw unreachable
                        ? new SQLException("Communications link failure", "08S01")
                        : new SQLException("NOT NULL constraint failed: test_visits.name");
            }
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
