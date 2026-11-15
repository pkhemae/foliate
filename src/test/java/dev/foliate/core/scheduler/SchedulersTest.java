package dev.foliate.core.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.foliate.FoliatePlugin;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Covers the facade's own logic. MockBukkit runs every scheduler on one simulated thread, so these
 * tests say nothing about region safety: that is what the Folia smoke test is for. Cancellation is
 * not covered here either, MockBukkit does not implement {@code ScheduledTask#cancel}.
 */
class SchedulersTest {

    private ServerMock server;
    private Schedulers schedulers;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        schedulers = new Schedulers(MockBukkit.load(FoliatePlugin.class));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void globalTaskRunsAfterItsDelay() {
        AtomicInteger runs = new AtomicInteger();

        schedulers.global().runLater(Ticks.duration(5), runs::incrementAndGet);

        server.getScheduler().performTicks(4);
        assertEquals(0, runs.get());
        server.getScheduler().performTicks(1);
        assertEquals(1, runs.get());
    }

    @Test
    void delayShorterThanATickStillWaitsOneTick() {
        AtomicInteger runs = new AtomicInteger();

        schedulers.global().runLater(Duration.ZERO, runs::incrementAndGet);

        assertEquals(0, runs.get());
        server.getScheduler().performTicks(1);
        assertEquals(1, runs.get());
    }

    @Test
    void failingTaskIsContainedAndLaterTasksStillRun() {
        AtomicInteger runs = new AtomicInteger();
        schedulers.global().execute(() -> {
            throw new IllegalStateException("boom");
        });
        schedulers.global().execute(runs::incrementAndGet);

        server.getScheduler().performTicks(1);

        assertEquals(1, runs.get());
    }

    @Test
    void supplyCompletesTheFutureWithTheValue() {
        CompletableFuture<Integer> future = schedulers.region(location()).supply(() -> 42);

        assertFalse(future.isDone());
        server.getScheduler().performTicks(1);

        assertEquals(42, future.getNow(null));
    }

    @Test
    void supplyReportsFailuresThroughTheFuture() {
        CompletableFuture<Integer> future = schedulers.global().supply(() -> {
            throw new IllegalStateException("boom");
        });

        server.getScheduler().performTicks(1);

        ExecutionException failure = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(IllegalStateException.class, failure.getCause());
    }

    @Test
    void asyncSupplyRunsOffTheCallingThread() throws Exception {
        Thread caller = Thread.currentThread();

        CompletableFuture<Thread> future = schedulers.async().supply(Thread::currentThread);
        server.getScheduler().performTicks(1);
        Thread worker = future.get(5, TimeUnit.SECONDS);

        assertFalse(caller.equals(worker));
    }

    @Test
    void entityTaskRunsForALiveEntity() {
        PlayerMock player = server.addPlayer();
        AtomicInteger runs = new AtomicInteger();

        schedulers.entity(player).execute(runs::incrementAndGet);
        server.getScheduler().performTicks(1);

        assertEquals(1, runs.get());
    }

    @Test
    void executeOrRunRunsInlineWhenTheContextIsCurrent() {
        AtomicInteger runs = new AtomicInteger();

        // MockBukkit treats the test thread as owning every region.
        schedulers.region(location()).executeOrRun(runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    private Location location() {
        return new Location(server.addSimpleWorld("world"), 0, 64, 0);
    }
}
