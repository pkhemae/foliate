package dev.foliate.core.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

/**
 * Brings a configuration file written by an older version of the plugin up to date before it is
 * mapped to {@link PluginConfig}.
 *
 * <p>Only needed when a key is renamed, moved or changes meaning: a new key with a default value
 * needs no migration. To add one, append a step to {@link #STEPS}; the step at index {@code i}
 * upgrades a file from version {@code i + 1}.
 */
final class ConfigMigrator {

    private static final String VERSION_KEY = "config-version";

    /** Example: {@code yaml -> rename(yaml, "old-name", "new.name")}. */
    private static final List<Consumer<ConfigurationSection>> STEPS = List.of();

    static final int CURRENT_VERSION = STEPS.size() + 1;

    private final List<Consumer<ConfigurationSection>> steps;

    ConfigMigrator() {
        this(STEPS);
    }

    ConfigMigrator(List<Consumer<ConfigurationSection>> steps) {
        this.steps = steps;
    }

    /**
     * Rewrites the file when it is older than the current version. Comments are lost here and
     * written back when the file is regenerated from {@link PluginConfig}.
     */
    void migrate(Path file) throws ConfigException, IOException {
        if (Files.notExists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(Files.readString(file));
        } catch (InvalidConfigurationException e) {
            throw new ConfigException("not valid YAML: " + e.getMessage(), e);
        }

        int target = steps.size() + 1;
        int version = yaml.getInt(VERSION_KEY, 1);
        if (version > target) {
            throw new ConfigException("written by a newer version of the plugin (config-version " + version + ")");
        }
        if (version == target) {
            return;
        }
        for (int from = Math.max(version, 1); from < target; from++) {
            steps.get(from - 1).accept(yaml);
        }
        yaml.set(VERSION_KEY, target);
        Files.writeString(file, yaml.saveToString());
    }

    /** Moves a value to a new path, keeping it when the administrator had changed it. */
    static void rename(ConfigurationSection yaml, String from, String to) {
        if (yaml.contains(from)) {
            yaml.set(to, yaml.get(from));
            yaml.set(from, null);
        }
    }
}
