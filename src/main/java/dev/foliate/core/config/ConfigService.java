package dev.foliate.core.config;

import de.exlll.configlib.ConfigurationException;
import de.exlll.configlib.NameFormatters;
import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurations;
import dev.foliate.core.module.Module;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.jspecify.annotations.Nullable;

/**
 * Loads {@code config.yml} and hands out the current {@link PluginConfig}.
 *
 * <p>Safe to read from any thread. A reload builds and validates a new configuration first and
 * only then publishes it, so readers see either the old one or the new one, never a mix, and an
 * invalid file leaves the previous configuration in place.
 *
 * <p>Read {@link #get()} each time a value is needed rather than keeping the result in a field:
 * that is what makes a reload take effect.
 */
public final class ConfigService implements Module {

    private static final YamlConfigurationProperties PROPERTIES = YamlConfigurationProperties.newBuilder()
            .setNameFormatter(NameFormatters.LOWER_KEBAB_CASE)
            .header("""
                    Foliate configuration.

                    This file is rewritten every time it is loaded: your values are kept, but
                    comments you add and keys the plugin does not know are removed.""")
            .build();

    private final Path file;
    private final ConfigMigrator migrator;
    private volatile @Nullable PluginConfig current;

    public ConfigService(Path dataDirectory) {
        this(dataDirectory, new ConfigMigrator());
    }

    ConfigService(Path dataDirectory, ConfigMigrator migrator) {
        this.file = dataDirectory.resolve("config.yml");
        this.migrator = migrator;
    }

    @Override
    public String name() {
        return "config";
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public void enable() throws ConfigException {
        current = load();
    }

    @Override
    public void reload() throws ConfigException {
        current = load();
    }

    /** The configuration as of the last successful load. */
    public PluginConfig get() {
        PluginConfig config = current;
        if (config == null) {
            throw new IllegalStateException("The configuration has not been loaded yet");
        }
        return config;
    }

    private PluginConfig load() throws ConfigException {
        try {
            Files.createDirectories(file.getParent());
            migrator.migrate(file);
            PluginConfig config = YamlConfigurations.update(file, PluginConfig.class, PROPERTIES);
            config.validate();
            return config;
        } catch (ConfigException e) {
            throw new ConfigException(file.getFileName() + " is invalid: " + e.getMessage(), e);
        } catch (ConfigurationException | IOException e) {
            throw new ConfigException(file.getFileName() + " could not be loaded: " + e.getMessage(), e);
        }
    }
}
