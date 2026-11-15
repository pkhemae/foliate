package dev.foliate.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Talks to a local HTTP server standing in for GitHub. */
class GitHubReleasesTest {

    private HttpServer server;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void tearDown() {
        server.stop(0);
    }

    @Test
    void latestReleaseIsRead() throws Exception {
        answer("/latest", 200, "{\"tag_name\": \"v1.4.0\", \"html_url\": \"https://example.test/v1.4.0\"}");

        Optional<ReleaseSource.Release> release = source("/latest").latest();

        assertEquals(new ReleaseSource.Release("v1.4.0", "https://example.test/v1.4.0"), release.orElseThrow());
    }

    @Test
    void repositoryWithoutAReleaseIsNotAnError() throws Exception {
        answer("/latest", 404, "{\"message\": \"Not Found\"}");

        assertTrue(source("/latest").latest().isEmpty());
    }

    @Test
    void redirectOfARenamedRepositoryIsFollowed() throws Exception {
        server.createContext("/old", exchange -> {
            exchange.getResponseHeaders().add("Location", "/latest");
            exchange.sendResponseHeaders(301, -1);
            exchange.close();
        });
        answer("/latest", 200, "{\"tag_name\": \"v2.0.0\", \"html_url\": \"https://example.test/v2.0.0\"}");

        assertEquals("v2.0.0", source("/old").latest().orElseThrow().version());
    }

    @Test
    void answersThatAreNotAReleaseBecomeAnIoError() {
        answer("/html", 200, "<html>rate limited</html>");
        answer("/null", 200, "{\"tag_name\": null, \"html_url\": \"https://example.test\"}");
        answer("/object", 200, "{\"tag_name\": {}, \"html_url\": \"https://example.test\"}");
        answer("/empty", 200, "{}");
        answer("/limited", 403, "{}");

        for (String path : new String[] {"/html", "/null", "/object", "/empty", "/limited"}) {
            assertThrows(IOException.class, source(path)::latest, path);
        }
    }

    @Test
    void unreachableServerGivesAMessageThatSaysSomething() {
        server.stop(0);

        IOException failure = assertThrows(IOException.class, source("/latest")::latest);

        assertTrue(failure.getMessage() != null && !failure.getMessage().isBlank(), failure.getMessage());
    }

    private GitHubReleases source(String path) {
        return new GitHubReleases(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + path));
    }

    private void answer(String path, int status, String body) {
        server.createContext(path, exchange -> {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
    }
}
