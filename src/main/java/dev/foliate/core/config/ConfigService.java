package dev.foliate.core.config;

import de.exlll.configlib.ConfigurationException;
import de.exlll.configlib.NameFormatters;
import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurationStore;
import dev.foliate.core.module.Module;
import dev.foliate.core.yaml.Yaml;
import dev.foliate.core.yaml.YamlException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Loads {@code config.yml} and hands out the current {@link PluginConfig}.
 *
 * <p>Safe to read from any thread. A reload builds and validates a new configuration first and
 * only then publishes it, so readers see either the old one or the new one, never a mix, and an
 * invalid file leaves both the previous configuration and the file itself untouched.
 *
 * <p>Read {@link #get()} each time a value is needed rather than keeping the result in a field:
 * that is what makes a reload take effect.
 */
public final class ConfigService implements Module {

    private static final String FILE_NAME = "config.yml";

    private static final YamlConfigurationProperties PROPERTIES = YamlConfigurationProperties.newBuilder()
            .setNameFormatter(NameFormatters.LOWER_KEBAB_CASE)
            .header("""
                    Foliate configuration.

                    The plugin rewrites this file when it adds new settings: your values are kept,
                    but comments you add and settings it does not know are removed.
                    Write true or false for on/off settings.""")
            .build();

    private final Path file;
    private final Logger logger;
    private final ConfigMigrator migrator;
    private final YamlConfigurationStore<PluginConfig> store = new YamlConfigurationStore<>(PluginConfig.class, PROPERTIES);
    private volatile @Nullable PluginConfig current;

    public ConfigService(Path dataDirectory, Logger logger) {
        this(dataDirectory, logger, new ConfigMigrator());
    }

    ConfigService(Path dataDirectory, Logger logger, ConfigMigrator migrator) {
        this.file = dataDirectory.resolve(FILE_NAME);
        this.logger = logger;
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

    /**
     * Reads, migrates, maps and validates the file, in that order, and only then touches the disk:
     * whatever goes wrong before, the administrator's file is as they left it.
     */
    private PluginConfig load() throws ConfigException {
        try {
            Files.createDirectories(file.getParent());
            String onDisk = Files.exists(file) ? Files.readString(file) : null;
            Map<String, Object> content = onDisk == null ? Map.of() : Yaml.parse(onDisk);

            Optional<Map<String, Object>> migrated = migrator.migrate(content);
            if (migrated.isPresent() && onDisk != null) {
                Path backup = file.resolveSibling(FILE_NAME + ".v" + content.get(ConfigMigrator.VERSION_KEY) + ".bak");
                Files.writeString(backup, onDisk);
                logger.info("{} was written by an older version: migrated, previous file kept as {}", FILE_NAME, backup.getFileName());
                content = migrated.get();
            }

            // An empty file is treated like a missing one: both get the defaults.
            PluginConfig config = content.isEmpty()
                    ? PluginConfig.defaults()
                    : store.read(new ByteArrayInputStream(Yaml.dump(content).getBytes(StandardCharsets.UTF_8)));
            config.validate();

            String rendered = render(config);
            if (!rendered.equals(onDisk)) {
                reportUnknownSettings(content, rendered);
                write(rendered);
            }
            return config;
        } catch (ConfigException e) {
            throw new ConfigException(FILE_NAME + " is invalid: " + e.getMessage(), e);
        } catch (YamlException e) {
            throw new ConfigException(FILE_NAME + " is " + e.getMessage(), e);
        } catch (ConfigurationException e) {
            throw new ConfigException(FILE_NAME + " has a setting with the wrong kind of value: " + e.getMessage(), e);
        } catch (IOException | UncheckedIOException e) {
            throw new ConfigException(FILE_NAME + " could not be read or written: " + e.getMessage(), e);
        }
    }

    private String render(PluginConfig config) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.write(config, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private void reportUnknownSettings(Map<String, Object> content, String rendered) throws YamlException {
        Set<String> unknown = new TreeSet<>(Yaml.flatten(content).keySet());
        unknown.removeAll(Yaml.flatten(Yaml.parse(rendered)).keySet());
        // A whole unknown section shows up as its leaves; a known list shows up as one key.
        for (String setting : unknown) {
            logger.warn("{}: '{}' is not a setting of this plugin and was removed from the file", FILE_NAME, setting);
        }
    }

    /** Writes through a temporary file, so a crash mid-write cannot leave a truncated config. */
    private void write(String rendered) throws IOException {
        Path temporary = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temporary, rendered);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
