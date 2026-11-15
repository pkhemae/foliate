package dev.foliate.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;

/** Reads the latest release of the plugin's GitHub repository. No token, nothing sent. */
public final class GitHubReleases implements ReleaseSource {

    /** {@code owner/name} of the repository whose releases are the plugin's. */
    private static final String REPOSITORY = "pkhemae/foliate";

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final int NOT_FOUND = 404;
    private static final int OK = 200;

    @Override
    public Optional<Release> latest() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases/latest"))
                .header("Accept", "application/vnd.github+json")
                .timeout(TIMEOUT)
                .build();
        try (HttpClient client = HttpClient.newBuilder().connectTimeout(TIMEOUT).build()) {
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
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
        } catch (JsonParseException | IllegalStateException | NullPointerException e) {
            throw new IOException("GitHub's answer could not be understood", e);
        }
    }
}
