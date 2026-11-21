package dev.foliate.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PluginYmlTest {

    /** How a subcommand declares a node it checks, next to it; on one line or on two. */
    private static final Pattern PERMISSION =
            Pattern.compile("static\\s+final\\s+String\\s+PERMISSION\\w*\\s*=\\s*\"([^\"]+)\"");

    /** Any text that looks like a command's node, constant or not: {@code "foliate.command.x"}. */
    private static final Pattern NODE = Pattern.compile("\"([a-z0-9_-]+\\.command(?:\\.[a-z0-9_-]+)+)\"");

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void declaredPermissionsAreRegisteredWithTheirDefaults() {
        PluginYml.registerPermissions(server);

        for (String node : PluginYml.permissions()) {
            assertNotNull(server.getPluginManager().getPermission(node), node);
        }
    }

    /**
     * Both ways: a node the code checks that the plugin.yml does not declare (it would have no
     * default, so nobody but operators would have it), and a declared node that no code checks.
     */
    @Test
    void theCodeAndThePluginYmlNameTheSamePermissions() throws IOException {
        Set<String> checked = new TreeSet<>();
        Set<String> mentioned = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                Matcher constant = PERMISSION.matcher(source);
                while (constant.find()) {
                    checked.add(constant.group(1));
                }
                Matcher node = NODE.matcher(source);
                while (node.find()) {
                    mentioned.add(node.group(1));
                }
            }
        }

        assertEquals(checked, new TreeSet<>(PluginYml.permissions()));
        // A node written inline, without a constant, would escape the comparison above.
        assertEquals(checked, mentioned, "write each command node as a PERMISSION constant next to its subcommand");
    }
}
