package dev.foliate.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleSkippedException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * The plugin's SQL database: a connection pool and the threads that queries run on.
 *
 * <p>Queries block, so they never run on a tick thread. {@link #submit} runs them on dedicated
 * storage threads rather than the server's async scheduler, for one reason: the scheduler is
 * unavailable while the plugin shuts down, and these threads let {@link #disable()} wait for the
 * writes still in flight before closing the pool.
 *
 * <p>Work runs in the order it was queued, except that {@link #submitFirst} goes ahead of
 * everything queued with {@link #submit}: a player waiting at the login screen for a read must not
 * wait behind the saves of every other player.
 */
public final class Database implements Module {

    private static final long SHUTDOWN_TIMEOUT_SECONDS = 15;
    private static final long CONNECTION_TIMEOUT_MILLIS = 10_000;

    /**
     * The MySQL driver waits for an answer forever by default: a query in flight when the server
     * stops answering would hold its thread, and the shutdown, for good. These apply unless
     * {@code storage.mysql.properties} sets them.
     */
    static final Map<String, String> MYSQL_DEFAULTS = Map.of(
            "connectTimeout", String.valueOf(CONNECTION_TIMEOUT_MILLIS),
            "socketTimeout", "30000");

    /**
     * How long the migrations' connection waits for an answer: longer than the lock that keeps two
     * servers from migrating at once, which MySQL holds for up to 60 seconds.
     */
    private static final int MIGRATION_NETWORK_TIMEOUT_MILLIS = 90_000;

    private final Path dataDirectory;
    private final Supplier<StorageSettings> settings;
    private final List<String> migrations;
    private final Logger logger;
    private volatile @Nullable HikariDataSource dataSource;
    private volatile @Nullable ExecutorService executor;

    /** Gives each job its place in the queue: first come, first served within a priority. */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * Jobs submitted and not finished. {@link #disable()} waits for it to fall to zero, which it
     * only does once the work chained after the last job has been submitted too: a job's own
     * callbacks run, and submit what follows it, before it counts as finished.
     */
    private final AtomicInteger unfinished = new AtomicInteger();

    /**
     * @param settings the current storage settings; read when the database starts
     * @param migrations the names of the SQL scripts under {@code migrations/}: each module that
     *     needs a table brings its own, named after it ({@code profile/001_create_profiles.sql}) and
     *     in the order they apply; the modules' lists go together in any order
     * @throws IllegalArgumentException when two scripts have the same name
     */
    public Database(
            Path dataDirectory, Supplier<StorageSettings> settings, List<String> migrations, Logger logger) {
        this.dataDirectory = dataDirectory;
        this.settings = settings;
        Migrations.requireUniqueNames(migrations);
        this.migrations = List.copyOf(migrations);
        this.logger = logger;
    }

    @Override
    public String name() {
        return "storage";
    }

    @Override
    public void enable() throws ModuleSkippedException, SQLException, IOException {
        StorageSettings settings = this.settings.get();
        if (!settings.enabled()) {
            throw new ModuleSkippedException("storage is turned off in config.yml");
        }
        if (dataSource != null) {
            // A second pool and set of threads would leak the first ones.
            throw new IllegalStateException("The database is already running");
        }

        HikariConfig pool = new HikariConfig();
        pool.setPoolName("Foliate");
        int threads;
        if (settings.type() == StorageSettings.Type.SQLITE) {
            Files.createDirectories(dataDirectory);
            pool.setJdbcUrl("jdbc:sqlite:" + dataDirectory.resolve("data.db").toAbsolutePath());
            // SQLite allows a single writer: one connection avoids "database is locked" errors.
            threads = 1;
            // Read by the driver as pragmas of every connection. Foreign keys are off by default in
            // SQLite and always on in MySQL: the same scripts must behave the same on both. The
            // write-ahead log syncs the disk less often than the rollback journal; it keeps recent
            // writes in data.db-wal until they are copied into data.db.
            pool.addDataSourceProperty("foreign_keys", "true");
            pool.addDataSourceProperty("journal_mode", "WAL");
        } else {
            StorageSettings.Mysql mysql = settings.mysql();
            pool.setJdbcUrl("jdbc:mysql://" + mysql.host() + ":" + mysql.port() + "/" + mysql.database());
            pool.setUsername(mysql.username());
            pool.setPassword(mysql.password());
            MYSQL_DEFAULTS.forEach(pool::addDataSourceProperty);
            mysql.properties().forEach(pool::addDataSourceProperty);
            // A save tells "no row to update" from the number of rows it changed: with affected
            // rows instead of matched rows, saving an unchanged value would look like a new row.
            pool.addDataSourceProperty("useAffectedRows", "false");
            threads = mysql.poolSize();
        }
        pool.setMaximumPoolSize(threads);
        // Fail fast when the database is unreachable instead of holding a login or the shutdown.
        pool.setConnectionTimeout(CONNECTION_TIMEOUT_MILLIS);

        HikariDataSource source = new HikariDataSource(pool);
        dataSource = source;
        try (Connection connection = source.getConnection()) {
            if (settings.type() == StorageSettings.Type.MYSQL) {
                // Reset by the pool when the connection goes back to it.
                connection.setNetworkTimeout(Runnable::run, MIGRATION_NETWORK_TIMEOUT_MILLIS);
            }
            new Migrations(logger, migrations, settings.type()).apply(connection);
        }

        AtomicInteger counter = new AtomicInteger();
        // A fixed pool whose queue hands out the urgent jobs first. Its threads are made as jobs
        // come and never retire: the same as Executors.newFixedThreadPool, with another queue.
        executor = new ThreadPoolExecutor(threads, threads, 0, TimeUnit.MILLISECONDS, new PriorityBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "Foliate-Storage-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Waits for queued work to finish, then closes the pool. Runs synchronously on the shutdown
     * thread, which is the only way to get anything done at that point.
     *
     * <p>Work is still accepted while it waits, so that what the last jobs chain after them (a
     * player's save behind their previous one) is done too. Once the queue is empty, or after
     * {@value #SHUTDOWN_TIMEOUT_SECONDS} seconds, nothing more is accepted and the pool closes:
     * what is still running then fails, and its callers report it.
     */
    @Override
    public void disable() {
        ExecutorService threads = executor;
        if (threads != null) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(SHUTDOWN_TIMEOUT_SECONDS);
            boolean drained = awaitUnfinished(deadline);
            // Not running from here on: what is submitted now fails at once instead of queuing.
            executor = null;
            threads.shutdown();
            if (!drained) {
                logger.warn(
                        "{} database operations did not finish within {} seconds and were stopped",
                        unfinished.get(),
                        SHUTDOWN_TIMEOUT_SECONDS);
                // What still waits in the queue would only fail against the closed pool: it fails
                // now, and the threads still running are interrupted.
                for (Runnable queued : threads.shutdownNow()) {
                    if (queued instanceof Job job) {
                        job.drop();
                    }
                }
            }
            try {
                threads.awaitTermination(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        HikariDataSource source = dataSource;
        if (source != null) {
            source.close();
            dataSource = null;
        }
    }

    /** Waits until no job is left, or the deadline. */
    private boolean awaitUnfinished(long deadline) {
        synchronized (unfinished) {
            while (unfinished.get() > 0) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                try {
                    TimeUnit.NANOSECONDS.timedWait(unfinished, left);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /** False when storage is turned off or failed to start. */
    public boolean isRunning() {
        return executor != null;
    }

    /**
     * Runs work on a storage thread with a connection of its own, after what was queued before it.
     * Safe to call from any thread, including tick threads: nothing blocks the caller.
     *
     * <p>The future fails with the {@link SQLException} thrown by the work, or with
     * {@link IllegalStateException} when the database is not running. Its callbacks run on the
     * storage thread unless they are made async: keep them short.
     */
    public <T extends @Nullable Object> CompletableFuture<T> submit(SqlFunction<T> work) {
        return queue(work, false);
    }

    /**
     * Like {@link #submit}, but ahead of everything queued with {@code submit}: for a read that
     * someone is waiting for, such as a player's data at login. It still waits for a free thread,
     * never for the writes queued before it, so it must not depend on them: to read a value that a
     * queued write is about to change, chain the read after that write.
     */
    public <T extends @Nullable Object> CompletableFuture<T> submitFirst(SqlFunction<T> work) {
        return queue(work, true);
    }

    private <T extends @Nullable Object> CompletableFuture<T> queue(SqlFunction<T> work, boolean first) {
        ExecutorService threads = executor;
        CompletableFuture<T> future = new CompletableFuture<>();
        if (threads == null) {
            future.completeExceptionally(new IllegalStateException("The database is not running"));
            return future;
        }
        unfinished.incrementAndGet();
        try {
            threads.execute(new Job(
                    first,
                    sequence.getAndIncrement(),
                    () -> {
                        try {
                            future.complete(call(work));
                        } catch (Throwable t) {
                            future.completeExceptionally(t);
                        } finally {
                            // After the callbacks, which may have submitted what follows this job.
                            finished();
                        }
                    },
                    () -> {
                        try {
                            future.completeExceptionally(new IllegalStateException("The database is shutting down"));
                        } finally {
                            finished();
                        }
                    }));
        } catch (RejectedExecutionException e) {
            finished();
            future.completeExceptionally(new IllegalStateException("The database is shutting down", e));
        }
        return future;
    }

    private void finished() {
        if (unfinished.decrementAndGet() == 0) {
            synchronized (unfinished) {
                unfinished.notifyAll();
            }
        }
    }

    /**
     * Runs work on the calling thread, ahead of anything queued with {@link #submit}. Only for
     * work that does not depend on queued writes, such as the schema migrations: to read data that
     * a queued write may be about to change, {@code submit} and wait for the future instead.
     */
    public <T extends @Nullable Object> T call(SqlFunction<T> work) throws SQLException {
        HikariDataSource source = dataSource;
        if (source == null) {
            throw new SQLException("The database is not running");
        }
        try (Connection connection = source.getConnection()) {
            return work.apply(connection);
        }
    }

    /**
     * A queued job: the urgent ones first, then in the order they came. {@code dropped} fails it
     * instead, when the pool closes before it ran.
     */
    private record Job(boolean first, long sequence, Runnable work, Runnable dropped)
            implements Runnable, Comparable<Job> {

        @Override
        public void run() {
            work.run();
        }

        void drop() {
            dropped.run();
        }

        @Override
        public int compareTo(Job other) {
            if (first != other.first) {
                return first ? -1 : 1;
            }
            return Long.compare(sequence, other.sequence);
        }
    }
}
