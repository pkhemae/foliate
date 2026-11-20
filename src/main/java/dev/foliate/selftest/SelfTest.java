package dev.foliate.selftest;

import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.module.PlayerCheck;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import dev.foliate.core.scheduler.TaskHandle;
import dev.foliate.core.storage.Database;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.util.Ticks;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Marker;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.Plugin;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Exercises the plugin on a real server, then stops the server. Started only when the JVM runs
 * with {@code -Dfoliate.selftest=true}, which {@code ./gradlew runFolia -PselfTest=true} does; the
 * build then fails unless the log contains {@link #PASSED}.
 *
 * <p>Never set that property on a production server: the self-test loads the spawn chunk, reloads
 * every module and stops the server.
 *
 * <p>Unit tests run everything on one thread, so they cannot catch what breaks on Folia: work on
 * the wrong region, a timer that survives its cancellation, a scheduler call that only fails on a
 * real server, a command that was never registered. This checks exactly those, once, against the real thing.
 *
 * <p>With {@code -Dfoliate.selftest.client=true} ({@code -PclientTest=true}) it then waits for a
 * real client to join, which the CI job starts, and checks what only a connected player shows: the
 * modules' own checks ({@link PlayerCheck}), the example menu opening (it knows that one command),
 * and the client still being connected a few seconds later. It then kicks the client and checks
 * that no module kept anything of the player.
 */
public final class SelfTest {

    public static final String PASSED = "FOLIATE SELF-TEST PASSED";
    public static final String FAILED = "FOLIATE SELF-TEST FAILED";
    /** Logged when the checks that need no player are done: the CI job then starts its client. */
    public static final String WAITING = "FOLIATE SELF-TEST WAITING FOR A PLAYER";

    private static final String PROPERTY = "foliate.selftest";
    private static final String EXPECTED_MODULES_PROPERTY = "foliate.selftest.expect";
    private static final String CLIENT_PROPERTY = "foliate.selftest.client";
    private static final String LANGUAGES_PROPERTY = "foliate.selftest.client.languages";
    private static final String TYPED_PROPERTY = "foliate.selftest.client.typed";
    private static final Duration TIMEOUT = Duration.ofSeconds(60);
    /** Starting a client takes much longer than starting the server. */
    private static final Duration CLIENT_TIMEOUT = Duration.ofMinutes(12);
    private static final Duration SETTLE = Duration.ofSeconds(3);
    private static final Duration RETRY = Duration.ofSeconds(2);
    private static final int ATTEMPTS = 10;
    private static final long CLEANUP_TIMEOUT_SECONDS = 5;

    private final Plugin plugin;
    private final Server server;
    private final Schedulers schedulers;
    private final ModuleManager modules;
    private final Database database;
    private final Logger logger;
    private final AtomicBoolean finished = new AtomicBoolean();
    /** The join the self-test is waiting for: replaced once a visit is over. */
    private final AtomicReference<CompletableFuture<Player>> nextPlayer =
            new AtomicReference<>(new CompletableFuture<>());
    private final boolean withClient = Boolean.getBoolean(CLIENT_PROPERTY);
    /** A command the client is made to type once it has joined, without the slash; empty for none. It must open the menu. */
    private final String typedCommand = System.getProperty(TYPED_PROPERTY, "").strip();
    /** Commands players typed, as the server saw them (with the slash), in order. */
    private final List<Typed> typedByPlayers = new CopyOnWriteArrayList<>();

    private record Typed(UUID player, String command) {}
    /** The language each visit's client is set to, in order: one visit per entry, one at least. */
    private final List<String> visitLanguages = Arrays.stream(System.getProperty(LANGUAGES_PROPERTY, "").split(","))
            .map(String::strip)
            .filter(language -> !language.isEmpty())
            .toList();
    private volatile @Nullable Entity marker;
    private volatile @Nullable Runnable releaseChunk;

    public SelfTest(Plugin plugin, Schedulers schedulers, ModuleManager modules, Database database) {
        this.plugin = plugin;
        this.server = plugin.getServer();
        this.schedulers = schedulers;
        this.modules = modules;
        this.database = database;
        this.logger = plugin.getSLF4JLogger();
    }

    public static boolean requested() {
        return Boolean.getBoolean(PROPERTY);
    }

    /**
     * Reports a failure that happened before the self-test could start, and stops the server so
     * the smoke test does not wait for a verdict that would never come.
     */
    public static void abort(Plugin plugin, String failure) {
        plugin.getSLF4JLogger().error("{}: {}", FAILED, failure);
        plugin.getServer().shutdown();
    }

    /** Call from {@code onEnable}. The checks begin once the server has started ticking. */
    public void start() {
        Duration timeout = withClient ? CLIENT_TIMEOUT : TIMEOUT;
        schedulers.async().runLater(timeout, () -> finish("timed out after " + timeout.toSeconds() + " seconds"));
        if (withClient) {
            plugin.getServer().getPluginManager().registerEvents(new Listener() {
                @EventHandler
                public void onJoin(PlayerJoinEvent event) {
                    nextPlayer.get().complete(event.getPlayer());
                }

                @EventHandler
                public void onCommand(PlayerCommandPreprocessEvent event) {
                    typedByPlayers.add(new Typed(event.getPlayer().getUniqueId(), event.getMessage()));
                }
            }, plugin);
        }
        schedulers.global().runLater(Ticks.duration(20), () -> run().whenComplete((ignored, error) -> {
            Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
            finish(cause == null ? null : String.valueOf(cause));
        }));
    }

    private CompletableFuture<Void> run() {
        World world = server.getWorlds().getFirst();
        Location spawn = world.getSpawnLocation();
        int chunkX = spawn.getBlockX() >> 4;
        int chunkZ = spawn.getBlockZ() >> 4;

        return schedulers.global()
                .run(() -> check(server.isGlobalTickThread(), "a global task did not run on the global region"))
                .thenCompose(ignored -> schedulers.async().run(() -> {
                    check(!server.isGlobalTickThread(), "an async task ran on the global region");
                    check(!server.isOwnedByCurrentRegion(spawn), "an async task ran on a region thread");
                }))
                .thenCompose(ignored -> schedulers.region(spawn).supply(() -> {
                    check(server.isOwnedByCurrentRegion(spawn), "a region task did not run on the owning region");
                    // Entity tasks only run while the entity is loaded: keep its chunk loaded.
                    world.addPluginChunkTicket(chunkX, chunkZ, plugin);
                    releaseChunk = () -> world.removePluginChunkTicket(chunkX, chunkZ, plugin);
                    // Never saved with the chunk, so nothing stays in the world if cleanup fails.
                    Entity spawned = world.spawn(spawn, Marker.class, entity -> entity.setPersistent(false));
                    marker = spawned;
                    return spawned;
                }))
                .thenCompose(marker -> entityTimerStopsWhenCancelled(marker)
                        .thenCompose(ignored -> teleportFromAnotherThread(marker, spawn))
                        .thenCompose(ignored -> removedEntityIsReported(marker)))
                .thenCompose(ignored -> schedulers.global().run(this::commandsAreRegistered))
                .thenCompose(ignored -> databaseAnswers())
                .thenCompose(ignored -> schedulers.async().run(this::everyModuleReloads))
                .thenRun(this::expectedModulesAreEnabled)
                .thenCompose(ignored -> withClient ? connectedPlayerIsServed() : CompletableFuture.completedFuture(null));
    }

    /** One visit per language asked for, or one: each is a client that joins, is checked, and is kicked. */
    private CompletableFuture<Void> connectedPlayerIsServed() {
        return visit(1, Math.max(1, visitLanguages.size()));
    }

    private CompletableFuture<Void> visit(int number, int total) {
        logger.info("{} (visit {} of {})", WAITING, number, total);
        String language = number <= visitLanguages.size() ? visitLanguages.get(number - 1) : "";
        return nextPlayer.get()
                .thenCompose(player -> {
                    // What the player types from now on belongs to this visit.
                    int typedBefore = typedByPlayers.size();
                    return after(SETTLE)
                        .thenCompose(ignored -> eventually(() -> schedulers.async().supply(() -> servedProblem(player, number, language))))
                        .thenCompose(ignored -> profileCommandRuns(player))
                        .thenCompose(ignored -> menuOpens(player, typedBefore))
                        .thenCompose(ignored -> after(SETTLE))
                        .thenCompose(ignored -> schedulers.entity(player)
                                .run(() -> check(player.isOnline(), "the client was disconnected")))
                        .thenCompose(ignored -> {
                            // Ready before the player is sent away, so that the next client is not missed.
                            nextPlayer.set(new CompletableFuture<>());
                            return quitIsCleanedUp(player);
                        });
                })
                .thenCompose(ignored -> number < total ? visit(number + 1, total) : CompletableFuture.completedFuture(null))
                // A client that drops while a check runs on its scheduler shows up as a retired entity.
                .exceptionallyCompose(error -> {
                    Throwable cause = error.getCause() != null ? error.getCause() : error;
                    return CompletableFuture.failedFuture(cause instanceof EntityRetiredException
                            ? new IllegalStateException("the client left before the checks were done")
                            : cause);
                });
    }

    private @Nullable String servedProblem(Player player, int visit, String language) {
        if (!player.isOnline()) {
            return "the client left before the checks were done";
        }
        if (!language.isEmpty() && !player.locale().getLanguage().equals(language)) {
            return player.getName() + " reports the language " + player.locale() + " instead of " + language;
        }
        List<PlayerCheck> checks = modules.enabledOf(PlayerCheck.class);
        if (checks.isEmpty()) {
            return "no module has a check for a connected player";
        }
        for (PlayerCheck check : checks) {
            String problem = check.problemWith(player, visit);
            if (problem != null) {
                return problem;
            }
        }
        logger.info(
                "Checked for a connected player, visit {}: {}",
                visit,
                checks.stream().map(check -> check.getClass().getSimpleName()).toList());
        return null;
    }

    /** Waits for this player to type the command the client was asked to, after {@code before} typed commands. */
    private CompletableFuture<Void> commandIsTyped(Player player, int before) {
        UUID id = player.getUniqueId();
        String expected = "/" + typedCommand;
        return eventually(() -> CompletableFuture.completedFuture(
                        typedByPlayers.stream().skip(before).anyMatch(typed -> typed.player().equals(id) && typed.command().equals(expected))
                                ? null
                                : "the client did not type " + expected))
                .thenRun(() -> logger.info("Typed by the client: {}", expected));
    }

    /** Kicks the player and checks that no module kept anything of them. */
    private CompletableFuture<Void> quitIsCleanedUp(Player player) {
        UUID id = player.getUniqueId();
        String name = player.getName();
        return schedulers.entity(player)
                .run(() -> player.kick(Component.text("Self-test done")))
                .thenCompose(ignored -> eventually(() -> schedulers.async().supply(() -> {
                    if (server.getPlayer(id) != null) {
                        return name + " is still listed as online";
                    }
                    for (PlayerCheck check : modules.enabledOf(PlayerCheck.class)) {
                        String problem = check.problemAfterQuit(id, name);
                        if (problem != null) {
                            return problem;
                        }
                    }
                    return null;
                })));
    }

    /**
     * Runs the profile command: as the player for their own, and from the console for a named
     * player, since the player may not look at others. What it says is not visible from here: this
     * catches a command that fails to run, which the log check turns into a failure.
     */
    private CompletableFuture<Void> profileCommandRuns(Player player) {
        if (!modules.enabledNames().contains("profiles")) {
            return CompletableFuture.completedFuture(null);
        }
        return schedulers.entity(player)
                .run(() -> check(
                        server.dispatchCommand(player, "foliate profile"), "'foliate profile' did not run as the player"))
                .thenCompose(ignored -> schedulers.global().run(() -> check(
                        server.dispatchCommand(server.getConsoleSender(), "foliate profile " + player.getName()),
                        "'foliate profile <player>' did not run from the console")));
    }

    /**
     * Has the menu command run as the player, by the server or, when the client was asked to type
     * it, by the client itself, and sees whether the menu opens on their screen. Typing a command
     * is only proven by its effect: the server hears of it before it checks the permission.
     */
    private CompletableFuture<Void> menuOpens(Player player, int typedBefore) {
        boolean typed = !typedCommand.isEmpty();
        if (!modules.enabledNames().contains("zmenu")) {
            return typed
                    ? CompletableFuture.failedFuture(new IllegalStateException("a typed command needs the menu plugin"))
                    : CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> command = typed
                ? commandIsTyped(player, typedBefore)
                : schedulers.entity(player).run(() ->
                        check(server.dispatchCommand(player, "foliate menu"), "'foliate menu' did not run as the player"));
        return command.thenCompose(ignored -> eventually(() -> schedulers.entity(player).supply(() ->
                player.getOpenInventory().getTopInventory().getType() != InventoryType.CRAFTING
                        ? null
                        : "the menu did not open on the player's screen")));
    }

    /** Tries again every few seconds until the attempt reports no problem, so a slow runner is not a failure. */
    private CompletableFuture<Void> eventually(Supplier<CompletableFuture<@Nullable String>> attempt) {
        return eventually(attempt, ATTEMPTS);
    }

    private CompletableFuture<Void> eventually(Supplier<CompletableFuture<@Nullable String>> attempt, int left) {
        return attempt.get().thenCompose(problem -> {
            if (problem == null) {
                return CompletableFuture.completedFuture(null);
            }
            if (left <= 1) {
                return CompletableFuture.failedFuture(new IllegalStateException(problem));
            }
            return after(RETRY).thenCompose(ignored -> eventually(attempt, left - 1));
        });
    }

    private CompletableFuture<Void> after(Duration delay) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        schedulers.async().runLater(delay, () -> done.complete(null));
        return done;
    }

    private CompletableFuture<Void> entityTimerStopsWhenCancelled(Entity marker) {
        CompletableFuture<Void> done = new CompletableFuture<>();
        AtomicInteger runs = new AtomicInteger();
        AtomicReference<@Nullable TaskHandle> handle = new AtomicReference<>();
        handle.set(schedulers.entity(marker).runTimer(Ticks.duration(1), Ticks.duration(1), () -> {
            if (!server.isOwnedByCurrentRegion(marker)) {
                done.completeExceptionally(new IllegalStateException("an entity task did not run on the owning region"));
            }
            TaskHandle timer = handle.get();
            if (runs.incrementAndGet() == 3 && timer != null) {
                timer.cancel();
                schedulers.entity(marker).runLater(Ticks.duration(10), () -> {
                    if (runs.get() == 3) {
                        done.complete(null);
                    } else {
                        done.completeExceptionally(new IllegalStateException(
                                "a cancelled timer kept running (" + runs.get() + " runs)"));
                    }
                });
            }
        }));
        return done;
    }

    private CompletableFuture<Void> teleportFromAnotherThread(Entity marker, Location spawn) {
        Location destination = spawn.clone().add(0, 5, 0);
        return schedulers.async()
                .supply(() -> schedulers.teleport(marker, destination))
                .thenCompose(future -> future)
                .thenCompose(moved -> schedulers.entity(marker).run(() -> {
                    check(moved, "teleport was refused");
                    check(marker.getLocation().getY() >= destination.getY() - 0.01, "teleport did not move the entity");
                }));
    }

    private CompletableFuture<Void> removedEntityIsReported(Entity marker) {
        return schedulers.entity(marker)
                .run(marker::remove)
                .thenCompose(ignored -> schedulers.entity(marker).run(() -> {}).handle((value, error) -> {
                    Throwable cause = error != null && error.getCause() != null ? error.getCause() : error;
                    check(cause instanceof EntityRetiredException, "work for a removed entity was not rejected");
                    return null;
                }));
    }

    /** Run from the console, on the global region, like a command typed in the server console. */
    private void commandsAreRegistered() {
        for (String command : List.of("foliate info", "foliate profile")) {
            check(server.dispatchCommand(server.getConsoleSender(), command), "'" + command + "' is not registered");
        }
    }

    private CompletableFuture<Void> databaseAnswers() {
        if (!database.isRunning()) {
            return CompletableFuture.completedFuture(null);
        }
        return database.submit(connection -> connection.isValid(5))
                .thenAccept(valid -> check(valid, "the database connection is not valid"));
    }

    /**
     * The smoke test says which modules it expects, so that an optional hook which quietly failed
     * to start (and is therefore simply absent) fails the run instead of going unnoticed.
     */
    private void expectedModulesAreEnabled() {
        String expected = System.getProperty(EXPECTED_MODULES_PROPERTY, "");
        List<String> missing = Arrays.stream(expected.split(","))
                .map(String::strip)
                .filter(module -> !module.isEmpty() && !modules.enabledNames().contains(module))
                .toList();
        check(missing.isEmpty(), "modules expected but not enabled: " + missing);
    }

    private void everyModuleReloads() {
        Map<String, Throwable> failures = modules.reloadAll();
        check(failures.isEmpty(), "modules failed to reload: " + failures.keySet());
    }

    private static void check(boolean condition, String failure) {
        if (!condition) {
            throw new IllegalStateException(failure);
        }
    }

    /** Logs the verdict once, undoes what the test did to the world, and stops the server. */
    private void finish(@Nullable String failure) {
        if (!finished.compareAndSet(false, true)) {
            return;
        }
        if (failure == null) {
            logger.info("{} (modules: {})", PASSED, String.join(", ", modules.enabledNames()));
        } else {
            logger.error("{}: {}", FAILED, failure);
        }
        // Whatever the verdict, and without waiting forever: if a region is what hangs, cleanup
        // cannot run there either, and the server still has to stop.
        cleanUp()
                .thenCompose(ignored -> schedulers.global().run(server::shutdown))
                .orTimeout(CLEANUP_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .exceptionally(error -> {
                    server.shutdown();
                    return null;
                });
    }

    private CompletableFuture<Void> cleanUp() {
        Entity spawned = marker;
        Runnable release = releaseChunk;
        CompletableFuture<Void> removed = spawned == null
                ? CompletableFuture.completedFuture(null)
                : schedulers.entity(spawned).run(spawned::remove).exceptionally(alreadyGone -> null);
        return removed.thenCompose(ignored -> release == null
                ? CompletableFuture.completedFuture(null)
                : schedulers.region(server.getWorlds().getFirst().getSpawnLocation()).run(release));
    }
}
