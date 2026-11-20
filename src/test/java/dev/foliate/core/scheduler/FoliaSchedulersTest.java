package dev.foliate.core.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Zombie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/**
 * Covers the facade's own logic. MockBukkit runs every scheduler on one simulated thread, so these
 * tests say nothing about region safety: that is what the Folia smoke test is for. Cancellation is
 * not covered here either: MockBukkit implements neither {@code ScheduledTask#cancel} nor
 * {@code ScheduledTask#isCancelled}. The one entity timer test plays the server with proxies.
 */
class FoliaSchedulersTest {

    private ServerMock server;
    private FoliaSchedulers schedulers;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
        schedulers = new FoliaSchedulers(MockBukkit.createMockPlugin());
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
    void supplyForARemovedEntityFailsInsteadOfHanging() {
        Entity entity = removedEntity();

        CompletableFuture<Integer> future = schedulers.entity(entity).supply(() -> 42);
        server.getScheduler().performTicks(1);

        ExecutionException failure = assertThrows(ExecutionException.class, future::get);
        assertInstanceOf(EntityRetiredException.class, failure.getCause());
    }

    @Test
    void executeForARemovedEntityIsRejected() {
        Entity entity = removedEntity();

        assertThrows(EntityRetiredException.class, () -> schedulers.entity(entity).execute(() -> {}));
    }

    @Test
    void chainThroughARemovedEntityCompletesExceptionally() {
        Entity entity = removedEntity();

        CompletableFuture<Void> chain = schedulers.global()
                .supply(() -> 42)
                .thenCompose(value -> schedulers.entity(entity).run(() -> {}));
        server.getScheduler().performTicks(2);

        assertTrue(chain.isCompletedExceptionally());
    }

    @Test
    void teleportOfARemovedEntityCompletesWithFalse() throws Exception {
        Entity entity = removedEntity();
        Location destination = entity.getLocation().add(0, 5, 0);

        // Called from a thread that does not own the entity, as an async callback would.
        CompletableFuture<Boolean> result = CompletableFuture.supplyAsync(() -> schedulers.teleport(entity, destination))
                .thenCompose(future -> future);
        server.getScheduler().performTicks(1);

        assertFalse(result.get(5, TimeUnit.SECONDS));
    }

    @Test
    void negativeAsyncDelayCountsAsZero() throws Exception {
        CompletableFuture<Boolean> ran = new CompletableFuture<>();

        schedulers.async().runLater(Duration.ofSeconds(-1), () -> ran.complete(true));
        server.getScheduler().performTicks(1);

        assertTrue(ran.get(5, TimeUnit.SECONDS));
    }

    @Test
    void executeOrRunContainsFailuresWhenRunningInline() {
        schedulers.region(location()).executeOrRun(() -> {
            throw new IllegalStateException("boom");
        });
    }

    @Test
    void executeOrRunRunsInlineWhenTheContextIsCurrent() {
        AtomicInteger runs = new AtomicInteger();

        // MockBukkit treats the test thread as owning every region.
        schedulers.region(location()).executeOrRun(runs::incrementAndGet);

        assertEquals(1, runs.get());
    }

    private Entity removedEntity() {
        Location location = location();
        Entity entity = location.getWorld().spawn(location, Zombie.class);
        entity.remove();
        return entity;
    }

    private Location location() {
        return new Location(server.addSimpleWorld("world"), 0, 64, 0);
    }

    /**
     * An entity removed while its timer is being scheduled has the retire callback run before the
     * task is known. MockBukkit has no entity scheduler to do that, so the server's side is played
     * by proxies. The task reports itself as not cancelled: the state a retired task is left in is
     * the server's business, so the timer must be forgotten because the callback ran.
     */
    @Test
    void timerOfAnEntityRetiredWhileBeingScheduledIsNotKeptUntilShutdown() {
        AtomicInteger cancelled = new AtomicInteger();
        ScheduledTask task = proxy(ScheduledTask.class, (method, args) -> {
            if (method.getName().equals("cancel")) {
                cancelled.incrementAndGet();
            }
            return null;
        });
        EntityScheduler scheduler = proxy(EntityScheduler.class, (method, args) -> {
            if (method.getName().equals("runAtFixedRate")) {
                ((Runnable) args[2]).run(); // retired before the task is returned
                return task;
            }
            return null;
        });
        Entity entity = proxy(Entity.class, (method, args) -> method.getName().equals("getScheduler") ? scheduler : null);

        schedulers.entity(entity).runTimer(Duration.ofSeconds(1), Duration.ofSeconds(1), () -> {});
        schedulers.shutdown();

        assertEquals(0, cancelled.get());
    }

    private interface Answer {
        Object to(java.lang.reflect.Method method, Object[] args);
    }

    /** An implementation of an interface answering through {@code answer}; unanswered primitives are zero. */
    private static <T> T proxy(Class<T> type, Answer answer) {
        Object proxy = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (self, method, args) -> {
            switch (method.getName()) {
                case "hashCode" -> {
                    return System.identityHashCode(self);
                }
                case "equals" -> {
                    return self == args[0];
                }
                case "toString" -> {
                    return type.getSimpleName() + " fake";
                }
                default -> {}
            }
            Object result = answer.to(method, args);
            if (result == null && method.getReturnType() == boolean.class) {
                return false;
            }
            return result;
        });
        return type.cast(proxy);
    }
}
