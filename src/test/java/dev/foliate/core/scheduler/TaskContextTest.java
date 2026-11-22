package dev.foliate.core.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

/** {@link TaskContext#executeOrDrop}: nobody to do it for is not an error, anything else is. */
class TaskContextTest {

    private ServerMock server;
    private final ManualSchedulers schedulers = new ManualSchedulers();
    private final AtomicInteger runs = new AtomicInteger();

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void workForAPlayerStillThereIsAccepted() {
        PlayerMock player = server.addPlayer();

        assertTrue(schedulers.entity(player).executeOrDrop(runs::incrementAndGet));
        schedulers.tick(1);

        assertEquals(1, runs.get());
    }

    @Test
    void workForAPlayerWhoLeftIsDropped() {
        PlayerMock player = server.addPlayer();
        schedulers.retire(player);

        assertFalse(schedulers.entity(player).executeOrDrop(runs::incrementAndGet));
        schedulers.tick(1);

        assertEquals(0, runs.get());
    }

    /** The server refuses new tasks once the plugin is disabled. */
    @Test
    void workRefusedBecauseThePluginIsDisabledIsDropped() {
        TaskContext disabled = refusing(new IllegalPluginAccessException("Plugin attempted to register task while disabled"));

        assertFalse(disabled.executeOrDrop(runs::incrementAndGet));
    }

    @Test
    void anyOtherFailureIsABugAndStillThrows() {
        TaskContext broken = refusing(new IllegalStateException("a bug"));

        assertThrows(IllegalStateException.class, () -> broken.executeOrDrop(runs::incrementAndGet));
    }

    /** A context whose {@code executeOrRun} fails as given: only the default method is under test. */
    private static TaskContext refusing(RuntimeException failure) {
        return new TaskContext() {
            @Override
            public void execute(Runnable task) {
                throw failure;
            }

            @Override
            public TaskHandle runLater(Duration delay, Runnable task) {
                throw failure;
            }

            @Override
            public TaskHandle runTimer(Duration initialDelay, Duration period, Runnable task) {
                throw failure;
            }

            @Override
            public boolean isCurrent() {
                return false;
            }

            @Override
            public void executeOrRun(Runnable task) {
                throw failure;
            }

            @Override
            public <T> CompletableFuture<T> supply(Supplier<T> supplier) {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }
}
