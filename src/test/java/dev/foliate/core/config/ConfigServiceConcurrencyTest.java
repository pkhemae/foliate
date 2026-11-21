package dev.foliate.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import de.exlll.configlib.Configuration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * Two services loaded at the same time from two threads. The plugin does not do that today, since
 * its {@code ModuleManager} loads modules one at a time, but {@link ConfigService} must stay safe
 * if a service is ever loaded outside it. ConfigLib reads and writes every file with one YAML
 * loader and one YAML dumper, shared by all its stores, that keep what they are working on in
 * fields: without the lock {@link ConfigService} takes around them, a file fails to load on a state
 * the other left half-built, or takes the other's values. Without it, this test failed every time
 * it was run.
 */
class ConfigServiceConcurrencyTest {

    private static final int ROUNDS = 2_000;

    /** Sections, lists and maps: what the library's shared dumper and loader keep track of. */
    @SuppressWarnings("UnusedVariable")
    @Configuration
    static final class Alpha implements ConfigRoot {

        private int configVersion = 1;
        private String name = "alpha";
        private List<String> words = new ArrayList<>(List.of("a1", "a2", "a3"));
        private Map<String, Integer> weights = new LinkedHashMap<>(Map.of("a", 1));
        private Section section = new Section();
        private List<Section> sections = new ArrayList<>(List.of(new Section()));

        private Alpha() {}

        @Override
        public int configVersion() {
            return configVersion;
        }

        @Override
        public void validate() {}

        String summary() {
            return name + words + weights + section.summary() + sections.stream().map(Section::summary).toList();
        }
    }

    /** Another shape, so that a value crossing from one file to the other shows. */
    @SuppressWarnings("UnusedVariable")
    @Configuration
    static final class Beta implements ConfigRoot {

        private int configVersion = 1;
        private List<Section> servers = new ArrayList<>(List.of(new Section(), new Section()));
        private Map<String, List<String>> groups = new LinkedHashMap<>(Map.of("b", List.of("b1")));
        private Section fallback = new Section();
        private String motd = "beta";

        private Beta() {}

        @Override
        public int configVersion() {
            return configVersion;
        }

        @Override
        public void validate() {}

        String summary() {
            return motd + servers.stream().map(Section::summary).toList() + groups + fallback.summary();
        }
    }

    @SuppressWarnings("UnusedVariable")
    @Configuration
    static final class Section {

        private String host = "localhost";
        private int port = 1;
        private List<String> tags = new ArrayList<>(List.of("t"));

        String summary() {
            return host + ":" + port + tags;
        }
    }

    @TempDir
    Path directory;

    @Test
    void twoFilesLoadedAtTheSameTimeKeepTheirOwnValues() throws Exception {
        Files.writeString(directory.resolve("alpha.yml"), """
                name: from-alpha
                words: [x, y, z]
                weights: {heavy: 9, light: 2}
                section: {host: alpha.example, port: 1111, tags: [one, two]}
                sections:
                  - {host: a1.example, port: 11, tags: [p]}
                  - {host: a2.example, port: 12, tags: [q, r]}
                """);
        Files.writeString(directory.resolve("beta.yml"), """
                servers:
                  - {host: b1.example, port: 21, tags: [s]}
                  - {host: b2.example, port: 22, tags: []}
                  - {host: b3.example, port: 23, tags: [u, v, w]}
                groups: {admins: [ann, bob], guests: []}
                fallback: {host: beta.example, port: 2222, tags: [three]}
                motd: from-beta
                """);
        ConfigService<Alpha> alpha = new ConfigService<>(
                directory, "alpha.yml", NOPLogger.NOP_LOGGER, Alpha.class, Alpha::new, List.of(), "Alpha.");
        ConfigService<Beta> beta = new ConfigService<>(
                directory, "beta.yml", NOPLogger.NOP_LOGGER, Beta.class, Beta::new, List.of(), "Beta.");
        // One at a time first: what each file holds once brought up to date, and what it reads as.
        alpha.enable();
        beta.enable();
        String alphaFile = Files.readString(directory.resolve("alpha.yml"));
        String betaFile = Files.readString(directory.resolve("beta.yml"));
        String alphaExpected = alpha.get().summary();
        String betaExpected = beta.get().summary();

        Queue<String> failures = new ConcurrentLinkedQueue<>();
        CountDownLatch together = new CountDownLatch(2);
        Thread first = Thread.ofPlatform().start(
                () -> reloadMany(alpha, together, failures, () -> alpha.get().summary(), alphaExpected));
        Thread second = Thread.ofPlatform().start(
                () -> reloadMany(beta, together, failures, () -> beta.get().summary(), betaExpected));
        assertTrue(first.join(Duration.ofMinutes(2)) && second.join(Duration.ofMinutes(2)), "still loading");
        assertEquals(List.of(), failures.stream().distinct().limit(10).toList());
        assertEquals(alphaFile, Files.readString(directory.resolve("alpha.yml")));
        assertEquals(betaFile, Files.readString(directory.resolve("beta.yml")));
    }

    /** Reloads the service over and over, once the other thread is ready to do the same. */
    private static void reloadMany(ConfigService<?> service, CountDownLatch together, Queue<String> failures,
            Supplier<String> summary, String expected) {
        together.countDown();
        try {
            together.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return;
        }
        for (int round = 0; round < ROUNDS; round++) {
            try {
                service.reload();
                String actual = summary.get();
                if (!actual.equals(expected)) {
                    failures.add(service.name() + " read " + actual);
                }
            } catch (ConfigException | RuntimeException e) {
                failures.add(service.name() + " failed: " + e);
            }
        }
    }
}
