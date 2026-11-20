package dev.foliate.modules.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Reads the latest release of the plugin's GitHub repository. No token and nothing about the
 * server is sent, beyond what any HTTP request reveals: its address and Java's user agent.
 */
public final class GitHubReleases implements ReleaseSource {

    /** {@code owner/name} of the repository whose releases are the plugin's. */
    private static final String REPOSITORY = "pkhemae/foliate";

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration TOTAL_TIMEOUT = Duration.ofSeconds(10);
    private static final int NOT_FOUND = 404;
    private static final int OK = 200;

    private final URI latestRelease;
    private final Duration totalTimeout;

    public GitHubReleases() {
        this(URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases/latest"));
    }

    GitHubReleases(URI latestRelease) {
        this(latestRelease, TOTAL_TIMEOUT);
    }

    GitHubReleases(URI latestRelease, Duration totalTimeout) {
        this.latestRelease = latestRelease;
        this.totalTimeout = totalTimeout;
    }

    @Override
    public Optional<Release> latest() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(latestRelease)
                .header("Accept", "application/vnd.github+json")
                .timeout(CONNECT_TIMEOUT)
                .build();
        // Redirects are followed: GitHub answers with one once a repository has been renamed.
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        try {
            // The request timeout stops at the response headers; this one bounds the whole exchange.
            HttpResponse<String> response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                    .get(totalTimeout.toMillis(), TimeUnit.MILLISECONDS);
            if (response.statusCode() == NOT_FOUND) {
                // No release published yet.
                return Optional.empty();
            }
            if (response.statusCode() != OK) {
                throw new IOException("GitHub answered " + response.statusCode());
            }
            JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
            return Optional.of(new Release(
                    release.get("tag_name").getAsString(), release.get("html_url").getAsString()));
        } catch (TimeoutException e) {
            throw new IOException("GitHub did not answer in time", e);
        } catch (ExecutionException e) {
            throw new IOException(describe(e.getCause()), e.getCause());
        } catch (RuntimeException e) {
            // Gson reports an answer of the wrong shape with several unchecked exceptions.
            throw new IOException("GitHub's answer could not be understood", e);
        } finally {
            // close() alone waits for the requests still running, which defeats the timeout above.
            client.shutdownNow();
            client.close();
        }
    }

    /** Network failures often come without a message: fall back to what kind of failure it was. */
    private static String describe(Throwable failure) {
        return failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName();
    }
}
