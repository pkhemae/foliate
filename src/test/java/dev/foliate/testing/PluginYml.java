package dev.foliate.testing;

import dev.foliate.core.yaml.Yaml;
import dev.foliate.core.yaml.YamlException;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.bukkit.Server;
import org.bukkit.permissions.Permission;
import org.bukkit.permissions.PermissionDefault;

/**
 * The permissions that {@code plugin.yml} declares (it is generated from {@code build.gradle.kts}),
 * for tests that need them without starting the real plugin: loading it would also start its
 * modules, whose listeners and timers would then fire in every test that adds a player.
 */
public final class PluginYml {

    private PluginYml() {}

    /** The names of the declared permissions. */
    public static List<String> permissions() {
        return new ArrayList<>(declared().keySet());
    }

    /** Registers the declared permissions, with their defaults, on a mock server. */
    public static void registerPermissions(Server server) {
        declared().forEach((name, content) -> {
            Map<?, ?> fields = (Map<?, ?>) content;
            PermissionDefault permissionDefault =
                    PermissionDefault.getByName(String.valueOf(fields.get("default")));
            server.getPluginManager()
                    .addPermission(new Permission(
                            name,
                            String.valueOf(fields.get("description")),
                            permissionDefault == null ? PermissionDefault.OP : permissionDefault));
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> declared() {
        try (InputStream in = PluginYml.class.getResourceAsStream("/plugin.yml")) {
            if (in == null) {
                throw new IllegalStateException("plugin.yml is not on the test classpath: run the build, not the IDE alone");
            }
            Map<String, Object> plugin = Yaml.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return (Map<String, Object>) plugin.getOrDefault("permissions", Map.of());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (YamlException e) {
            throw new IllegalStateException("plugin.yml is not valid YAML: " + e.getMessage(), e);
        }
    }
}
