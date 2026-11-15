package dev.foliate.modules.update;

import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleSkippedException;
import dev.foliate.core.scheduler.Schedulers;
import java.io.IOException;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;

/**
 * Looks once, at startup, for a release newer than the running version, and says so in the
 * console. It never downloads or installs anything.
 *
 * <p>It is an outbound connection, so it can be turned off in {@code config.yml} and must be
 * mentioned on the plugin's download page. A failed check is not an error: the server may simply
 * have no internet access.
 */
public final class UpdateCheckModule implements Module {

    private final BooleanSupplier enabled;
    private final Schedulers schedulers;
    private final Logger logger;
    private final String currentVersion;
    private final ReleaseSource releases;

    public UpdateCheckModule(
            BooleanSupplier enabled, Schedulers schedulers, Logger logger, String currentVersion, ReleaseSource releases) {
        this.enabled = enabled;
        this.schedulers = schedulers;
        this.logger = logger;
        this.currentVersion = currentVersion;
        this.releases = releases;
    }

    @Override
    public String name() {
        return "updates";
    }

    @Override
    public void enable() throws ModuleSkippedException {
        if (!enabled.getAsBoolean()) {
            throw new ModuleSkippedException("the update check is turned off in config.yml");
        }
        // A network call: never on the thread that starts the server.
        schedulers.async().execute(this::check);
    }

    void check() {
        try {
            Optional<ReleaseSource.Release> latest = releases.latest();
            if (latest.isEmpty()) {
                return;
            }
            ReleaseSource.Release release = latest.get();
            if (Version.parse(release.version()).compareTo(Version.parse(currentVersion)) > 0) {
                logger.info(
                        "Version {} is available (this server runs {}): {}",
                        release.version(),
                        currentVersion,
                        release.url());
            }
        } catch (IOException | RuntimeException | LinkageError e) {
            // Includes a version that cannot be compared, and classes gone because the server
            // stopped while the request was in flight. None of it is worth a stack trace.
            logger.info(
                    "Could not check for a newer version: {}",
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
