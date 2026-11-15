package dev.foliate.modules.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.module.ModuleSkippedException;
import dev.foliate.core.scheduler.ManualSchedulers;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.helpers.NOPLogger;

class UpdateCheckModuleTest {

    @TempDir
    Path directory;

    private ConfigService<PluginConfig> config;
    private final ManualSchedulers schedulers = new ManualSchedulers();
    private final List<String> logged = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
    }

    @Test
    void newerReleaseIsAnnouncedWithItsLink() throws Exception {
        UpdateCheckModule module = module("1.0.0", () -> Optional.of(release("v1.1.0")));

        module.enable();
        assertTrue(logged.isEmpty(), "the network call must not happen on the startup thread");
        schedulers.tick(1);

        assertEquals(List.of("async"), schedulers.contextsUsed());
        assertEquals(1, logged.size());
        assertTrue(logged.getFirst().contains("v1.1.0"), logged.getFirst());
        assertTrue(logged.getFirst().contains("https://example.test/v1.1.0"), logged.getFirst());
    }

    @Test
    void nothingIsSaidWhenUpToDateOrAhead() throws Exception {
        module("1.1.0", () -> Optional.of(release("v1.1.0"))).check();
        module("1.2.0-SNAPSHOT", () -> Optional.of(release("v1.1.0"))).check();
        module("1.0.0", Optional::empty).check();

        assertTrue(logged.isEmpty(), logged.toString());
    }

    @Test
    void failedCheckIsMentionedOnceAndNotAsAnError() {
        module("1.0.0", () -> {
                    throw new IOException("no route to host");
                })
                .check();

        assertEquals(1, logged.size());
        assertTrue(logged.getFirst().contains("no route to host"), logged.getFirst());
    }

    @Test
    void failuresWithoutAMessageAndUnreadableVersionsAreStillExplained() {
        module("1.0.0", () -> {
                    throw new java.net.ConnectException();
                })
                .check();
        module("1.0.0", () -> Optional.of(release("nightly"))).check();
        module("1.0.0", () -> {
                    throw new UnsupportedOperationException();
                })
                .check();

        assertEquals(3, logged.size());
        assertTrue(logged.stream().noneMatch(line -> line.endsWith("null")), logged.toString());
    }

    @Test
    void moduleIsSkippedWhenTurnedOff() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "update-check: false\n");
        config.reload();

        assertThrows(ModuleSkippedException.class, module("1.0.0", Optional::empty)::enable);
        assertEquals(0, schedulers.pendingTasks());
    }

    private UpdateCheckModule module(String current, ReleaseSource releases) {
        return new UpdateCheckModule(() -> config.get().updateCheck(), schedulers, recordingLogger(), current, releases);
    }

    private static ReleaseSource.Release release(String version) {
        return new ReleaseSource.Release(version, "https://example.test/" + version);
    }

    /** Records info messages and fails the test on a warning or an error. */
    private Logger recordingLogger() {
        return (Logger) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[] {Logger.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals("info")) {
                        String message = String.valueOf(arguments[0]);
                        for (int i = 1; i < arguments.length; i++) {
                            Object argument = arguments[i];
                            for (Object value : argument instanceof Object[] many ? many : new Object[] {argument}) {
                                message = message.replaceFirst("\\{}", java.util.regex.Matcher.quoteReplacement(String.valueOf(value)));
                            }
                        }
                        logged.add(message);
                        return null;
                    }
                    if (method.getName().equals("error") || method.getName().equals("warn")) {
                        throw new AssertionError("unexpected " + method.getName() + " log: " + arguments[0]);
                    }
                    return method.getReturnType() == boolean.class ? Boolean.TRUE : null;
                });
    }
}
