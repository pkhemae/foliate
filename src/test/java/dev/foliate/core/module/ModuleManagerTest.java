package dev.foliate.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
