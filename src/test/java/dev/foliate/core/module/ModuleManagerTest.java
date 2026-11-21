package dev.foliate.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class ModuleManagerTest {

    private final List<String> events = new ArrayList<>();
    private final ModuleManager manager = new ModuleManager(NOPLogger.NOP_LOGGER);

    @Test
    void enablesInOrderAndDisablesInReverse() {
        manager.register(new RecordingModule("a"));
        manager.register(new RecordingModule("b"));

        assertTrue(manager.enableAll());
        manager.disableAll();

        assertEquals(List.of("enable a", "enable b", "disable b", "disable a"), events);
    }

    @Test
    void enabledModulesCanBeAskedForACapability() {
        class Capable extends RecordingModule implements Runnable {
            Capable() {
                super("capable");
            }

            @Override
            public void run() {}
        }
        manager.register(new RecordingModule("plain"));
        manager.register(new Capable());
        manager.register(new RecordingModule("broken").failingOn("enable"));
        manager.enableAll();

        assertEquals(1, manager.enabledOf(Runnable.class).size());
        assertEquals(2, manager.enabledOf(Module.class).size());
    }

    @Test
    void moduleFailingToEnableDoesNotStopTheOthers() {
        manager.register(new RecordingModule("a"));
        manager.register(new RecordingModule("broken").failingOn("enable"));
        manager.register(new RecordingModule("c"));

        assertTrue(manager.enableAll());

        assertEquals(List.of("a", "c"), manager.enabledNames());
    }

    @Test
    void moduleFailingToEnableIsCleanedUpOnceThenLeftAlone() {
        manager.register(new RecordingModule("broken").failingOn("enable"));
        manager.enableAll();

        manager.reloadAll();
        manager.disableAll();

        assertEquals(List.of("enable broken", "disable broken"), events);
    }

    @Test
    void requiredModuleFailingToEnableStopsEverything() {
        manager.register(new RecordingModule("a"));
        manager.register(new RecordingModule("broken").failingOn("enable").markRequired());
        manager.register(new RecordingModule("c"));

        boolean started = manager.enableAll();

        assertFalse(started);
        assertEquals(List.of("enable a", "enable broken", "disable broken", "disable a"), events);
        assertTrue(manager.enabledNames().isEmpty());
    }

    @Test
    void skippedModuleIsLeftOutWithoutBeingCleanedUp() {
        manager.register(new Module() {
            @Override
            public String name() {
                return "optional";
            }

            @Override
            public void enable() throws ModuleSkippedException {
                throw new ModuleSkippedException("turned off");
            }

            @Override
            public void disable() {
                events.add("disable optional");
            }
        });
        manager.register(new RecordingModule("b"));

        assertTrue(manager.enableAll());
        manager.disableAll();

        assertEquals(List.of("enable b", "disable b"), events);
    }

    /** The plugin cannot run without a required module, whatever the reason it gives. */
    @Test
    void requiredModuleThatSkipsItselfStopsEverything() {
        manager.register(new RecordingModule("a"));
        manager.register(new Module() {
            @Override
            public String name() {
                return "needed";
            }

            @Override
            public boolean required() {
                return true;
            }

            @Override
            public void enable() throws ModuleSkippedException {
                throw new ModuleSkippedException("turned off");
            }
        });

        assertFalse(manager.enableAll());
        assertEquals(List.of("enable a", "disable a"), events);
        assertTrue(manager.enabledNames().isEmpty());
    }

    @Test
    void twoModulesCannotShareAName() {
        manager.register(new RecordingModule("a"));

        assertThrows(IllegalArgumentException.class, () -> manager.register(new RecordingModule("a")));
    }

    /** An empty report would read as a success. */
    @Test
    void reloadingModulesThatAreNotEnabledIsRejected() {
        manager.register(new RecordingModule("a"));
        assertThrows(IllegalStateException.class, manager::reloadAll);

        manager.enableAll();
        manager.disableAll();

        assertThrows(IllegalStateException.class, manager::reloadAll);
    }

    @Test
    void reloadReportsFailuresAndKeepsGoing() {
        manager.register(new RecordingModule("broken").failingOn("reload"));
        manager.register(new RecordingModule("b"));
        manager.enableAll();
        events.clear();

        Map<String, Throwable> failures = manager.reloadAll();

        assertEquals(List.of("broken"), List.copyOf(failures.keySet()));
        assertEquals(List.of("reload broken", "reload b"), events);
    }

    @Test
    void disableFailureDoesNotStopTheOthers() {
        manager.register(new RecordingModule("a"));
        manager.register(new RecordingModule("broken").failingOn("disable"));
        manager.enableAll();
        events.clear();

        manager.disableAll();

        assertEquals(List.of("disable broken", "disable a"), events);
        assertTrue(manager.enabledNames().isEmpty());
    }

    @Test
    void registeringAfterEnableIsRejectedEvenWhenNothingEnabled() {
        manager.register(new RecordingModule("broken").failingOn("enable"));
        manager.enableAll();

        assertThrows(IllegalStateException.class, () -> manager.register(new RecordingModule("late")));
    }

    @Test
    void enablingTwiceIsRejected() {
        manager.register(new RecordingModule("a"));
        manager.enableAll();

        assertThrows(IllegalStateException.class, manager::enableAll);
        assertEquals(List.of("a"), manager.enabledNames());
    }

    /**
     * Stopping the server during a reload must not wait for a reload stuck on a region that no
     * longer ticks: the reload is interrupted and stops there.
     */
    @Test
    void disablingDuringAReloadInterruptsItAndSkipsTheModulesLeft() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch waiting = new CountDownLatch(1);
        manager.register(new WaitingModule("waiting", waiting, calls));
        manager.register(new CallModule("after", calls));
        manager.enableAll();
        AtomicBoolean interruptKept = new AtomicBoolean();
        CompletableFuture<Map<String, Throwable>> reload = CompletableFuture.supplyAsync(() -> {
            Map<String, Throwable> failures = manager.reloadAll();
            interruptKept.set(Thread.interrupted());
            return failures;
        }, ModuleManagerTest::onNewThread);
        assertTrue(waiting.await(5, TimeUnit.SECONDS));

        assertTimeoutPreemptively(Duration.ofSeconds(5), manager::disableAll);

        Map<String, Throwable> failures = reload.get(5, TimeUnit.SECONDS);
        assertEquals(List.of("waiting", "after"), List.copyOf(failures.keySet()));
        assertInstanceOf(InterruptedException.class, failures.get("after"));
        assertTrue(interruptKept.get(), "the interrupt status is kept for the caller");
        assertEquals(List.of("reload waiting", "disable after", "disable waiting"), calls);
    }

    @Test
    void reloadWaitingForTheLockWhenDisablingStartsDoesNotRun() throws Exception {
        List<String> calls = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch waiting = new CountDownLatch(1);
        manager.register(new WaitingModule("waiting", waiting, calls));
        manager.enableAll();
        CompletableFuture<Map<String, Throwable>> first =
                CompletableFuture.supplyAsync(manager::reloadAll, ModuleManagerTest::onNewThread);
        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        CompletableFuture<@Nullable Throwable> queued = new CompletableFuture<>();
        Thread second = new Thread(() -> {
            try {
                manager.reloadAll();
                queued.complete(null);
            } catch (RuntimeException e) {
                queued.complete(e);
            }
        });
        second.start();
        while (second.getState() != Thread.State.BLOCKED) {
            Thread.onSpinWait();
        }

        manager.disableAll();

        first.get(5, TimeUnit.SECONDS);
        assertInstanceOf(IllegalStateException.class, queued.get(5, TimeUnit.SECONDS));
        assertEquals(List.of("reload waiting", "disable waiting"), calls);
    }

    /** The interrupt is for the reload: a thread that has finished reloading keeps running in peace. */
    @Test
    void disablingAfterAReloadDoesNotInterruptItsThread() throws Exception {
        manager.register(new RecordingModule("a"));
        manager.enableAll();
        CountDownLatch reloaded = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Boolean> undisturbed = CompletableFuture.supplyAsync(() -> {
            manager.reloadAll();
            reloaded.countDown();
            try {
                return release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }, ModuleManagerTest::onNewThread);
        assertTrue(reloaded.await(5, TimeUnit.SECONDS));

        manager.disableAll();
        release.countDown();

        assertTrue(undisturbed.get(5, TimeUnit.SECONDS));
    }

    /** A thread of its own, so that an interrupt cannot linger on a pooled thread other tests use. */
    private static void onNewThread(Runnable task) {
        new Thread(task).start();
    }

    /** Records its calls in a list that several threads may write to. */
    private static class CallModule implements Module {

        private final String name;
        final List<String> calls;

        CallModule(String name, List<String> calls) {
            this.name = name;
            this.calls = calls;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void enable() {}

        @Override
        public void reload() throws Exception {
            calls.add("reload " + name);
        }

        @Override
        public void disable() {
            calls.add("disable " + name);
        }
    }

    /** Waits for good when reloaded, as a reload waiting for a halted region does, until interrupted. */
    private static final class WaitingModule extends CallModule {

        private final CountDownLatch waiting;

        WaitingModule(String name, CountDownLatch waiting, List<String> calls) {
            super(name, calls);
            this.waiting = waiting;
        }

        @Override
        public void reload() throws Exception {
            super.reload();
            waiting.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Exception("interrupted, nothing changed", e);
            }
        }
    }

    /** Records every lifecycle call, and can be told to throw from one of them. */
    private class RecordingModule implements Module {

        private final String name;
        private String failing = "";
        private boolean required;

        RecordingModule(String name) {
            this.name = name;
        }

        RecordingModule failingOn(String step) {
            failing = step;
            return this;
        }

        RecordingModule markRequired() {
            required = true;
            return this;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public boolean required() {
            return required;
        }

        @Override
        public void enable() {
            step("enable");
        }

        @Override
        public void reload() {
            step("reload");
        }

        @Override
        public void disable() {
            step("disable");
        }

        private void step(String step) {
            events.add(step + " " + name);
            if (step.equals(failing)) {
                throw new IllegalStateException("boom");
            }
        }
    }
}
