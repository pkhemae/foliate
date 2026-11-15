package dev.foliate.update;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.module.Module;
import dev.foliate.core.module.ModuleSkippedException;
import dev.foliate.core.scheduler.Schedulers;
import java.io.IOException;
import java.util.Optional;
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

    private final ConfigService config;
    private final Schedulers schedulers;
    private final Logger logger;
    private final String currentVersion;
    private final ReleaseSource releases;

    public UpdateCheckModule(
            ConfigService config, Schedulers schedulers, Logger logger, String currentVersion, ReleaseSource releases) {
        this.config = config;
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
        if (!config.get().updateCheck()) {
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
        } catch (IOException | IllegalArgumentException e) {
            logger.info("Could not check for a newer version: {}", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
