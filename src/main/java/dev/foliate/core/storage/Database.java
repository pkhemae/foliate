package dev.foliate.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.config.PluginConfig;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleSkippedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The plugin's SQL database: a connection pool and the threads that queries run on.
 *
 * <p>Queries block, so they never run on a tick thread. {@link #submit} runs them on dedicated
 * storage threads rather than the server's async scheduler, for one reason: the scheduler is
 * unavailable while the plugin shuts down, and these threads let {@link #disable()} wait for the
 * writes still in flight before closing the pool.
 */
public final class Database implements Module {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 15;
    private static final long CONNECTION_TIMEOUT_MILLIS = 10_000;

    private final Path dataDirectory;
    private final ConfigService config;
    private final Logger logger;
    private volatile @Nullable HikariDataSource dataSource;
    private volatile @Nullable ExecutorService executor;

    public Database(Path dataDirectory, ConfigService config, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public void enable() throws ModuleSkippedException, SQLException, IOException {
        PluginConfig.Storage settings = config.get().storage();
        if (!settings.enabled()) {
            throw new ModuleSkippedException("storage is turned off in config.yml");
        }

        HikariConfig pool = new HikariConfig();
        pool.setPoolName("Foliate");
        int threads;
        if (settings.type() == PluginConfig.Storage.Type.SQLITE) {
            Files.createDirectories(dataDirectory);
            pool.setJdbcUrl("jdbc:sqlite:" + dataDirectory.resolve("data.db").toAbsolutePath());
            // SQLite allows a single writer: one connection avoids "database is locked" errors.
            threads = 1;
        } else {
            PluginConfig.Mysql mysql = settings.mysql();
            pool.setJdbcUrl("jdbc:mysql://" + mysql.host() + ":" + mysql.port() + "/" + mysql.database());
            pool.setUsername(mysql.username());
            pool.setPassword(mysql.password());
            threads = mysql.poolSize();
        }
        pool.setMaximumPoolSize(threads);
        // Fail fast when the database is unreachable instead of holding a login or the shutdown.
        pool.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);

        HikariDataSource source = new HikariDataSource(pool);
        dataSource = source;
        try (Connection connection = source.getConnection()) {
            new Migrations(logger).apply(connection);
        }

        AtomicInteger counter = new AtomicInteger();
        executor = Executors.newFixedThreadPool(threads, task -> {
            Thread thread = new Thread(task, "Foliate-Storage-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Waits for queued work to finish, then closes the pool. Runs synchronously on the shutdown
     * thread, which is the only way to get anything done at that point.
     */
    @Override
    public void disable() {
        ExecutorService threads = executor;
        if (threads != null) {
            threads.shutdown();
            try {
                if (!threads.awaitTermination(SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    logger.warn("Some database writes did not finish within {} seconds", SHUTDOWN_TIMEOUT_SECONDS);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            executor = null;
        }
        HikariDataSource source = dataSource;
        if (source != null) {
            source.close();
            dataSource = null;
        }
    }

    /** False when storage is turned off or failed to start. */
    public boolean isRunning() {
        return executor != null;
    }

    /**
     * Runs work on a storage thread with a connection of its own. Safe to call from any thread,
     * including tick threads: nothing blocks the caller.
     *
     * <p>The future fails with the {@link SQLException} thrown by the work, or with
     * {@link IllegalStateException} when the database is not running.
     */
    public <T> CompletableFuture<T> submit(SqlFunction<T> work) {
        ExecutorService threads = executor;
        CompletableFuture<T> future = new CompletableFuture<>();
        if (threads == null) {
            future.completeExceptionally(new IllegalStateException("The database is not running"));
            return future;
        }
        try {
            threads.execute(() -> {
                try {
                    future.complete(call(work));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            future.completeExceptionally(new IllegalStateException("The database is shutting down", e));
        }
        return future;
    }

    /**
     * Runs work on the calling thread, ahead of anything queued with {@link #submit}. Only for
     * work that does not depend on queued writes, such as the schema migrations: to read data that
     * a queued write may be about to change, {@code submit} and wait for the future instead.
     */
    public <T> T call(SqlFunction<T> work) throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null) {
            throw new SQLException("The database is not running");
        }
        try (Connection connection = source.getConnection()) {
            return work.apply(connection);
        }
    }
}
