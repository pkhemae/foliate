package dev.foliate.core.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        manager.enableAll();
        manager.disableAll();

        assertEquals(List.of("enable a", "enable b", "disable b", "disable a"), events);
    }

    @Test
    void moduleFailingToEnableDoesNotStopTheOthers() {
        manager.register(new RecordingModule("a"));
        manager.register(new FailingModule("broken", true, false));
        manager.register(new RecordingModule("c"));

        manager.enableAll();

        assertEquals(List.of("a", "c"), manager.enabledNames());
    }

    @Test
    void moduleThatFailedToEnableIsNeitherReloadedNorDisabled() {
        manager.register(new FailingModule("broken", true, false));
        manager.enableAll();

        manager.reloadAll();
        manager.disableAll();

        assertEquals(List.of(), events);
    }

    @Test
    void reloadReportsFailuresAndKeepsGoing() {
        manager.register(new FailingModule("broken", false, true));
        manager.register(new RecordingModule("b"));
        manager.enableAll();
        events.clear();

        Map<String, Exception> failures = manager.reloadAll();

        assertEquals(List.of("broken"), List.copyOf(failures.keySet()));
        assertEquals(List.of("reload b"), events);
    }

    @Test
    void disableFailureDoesNotStopTheOthers() {
        manager.register(new RecordingModule("a"));
        manager.register(new Module() {
            @Override
            public String name() {
                return "broken";
            }

            @Override
            public void enable() {}

            @Override
            public void disable() {
                throw new IllegalStateException("boom");
            }
        });
        manager.enableAll();
        events.clear();

        manager.disableAll();

        assertEquals(List.of("disable a"), events);
        assertTrue(manager.enabledNames().isEmpty());
    }

    @Test
    void registeringAfterEnableIsRejected() {
        manager.register(new RecordingModule("a"));
        manager.enableAll();

        assertThrows(IllegalStateException.class, () -> manager.register(new RecordingModule("late")));
    }

    private class RecordingModule implements Module {

        private final String name;

        RecordingModule(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void enable() {
            events.add("enable " + name);
        }

        @Override
        public void reload() {
            events.add("reload " + name);
        }

        @Override
        public void disable() {
            events.add("disable " + name);
        }
    }

    private final class FailingModule extends RecordingModule {

        private final boolean failOnEnable;
        private final boolean failOnReload;

        FailingModule(String name, boolean failOnEnable, boolean failOnReload) {
            super(name);
            this.failOnEnable = failOnEnable;
            this.failOnReload = failOnReload;
        }

        @Override
        public void enable() {
            if (failOnEnable) {
                throw new IllegalStateException("boom");
            }
        }

        @Override
        public void reload() {
            if (failOnReload) {
                throw new IllegalStateException("boom");
            }
        }
    }
}
