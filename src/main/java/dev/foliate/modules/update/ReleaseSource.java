package dev.foliate.modules.update;

import java.io.IOException;
import java.util.Optional;

/** Where the latest released version is looked up. */
@FunctionalInterface
public interface ReleaseSource {

    /**
     * Blocks while it asks: call it off the tick threads.
     *
     * @return the latest release, or empty when nothing has been released yet
     * @throws IOException when the answer could not be obtained
     */
    Optional<Release> latest() throws IOException, InterruptedException;

    /**
     * @param version as published, for example {@code v1.4.0}
     * @param url the page an administrator can download it from
     */
    record Release(String version, String url) {}
}
