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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Loads {@code config.yml} into a settings class and hands out the current instance. It knows
 * nothing about what the settings are: the class, its defaults and its migrations are given by the
 * caller.
 *
 * <p>Safe to read from any thread. A reload builds and validates a new configuration first and
 * only then publishes it, so readers see either the old one or the new one, never a mix, and an
 * invalid file leaves both the previous configuration and the file itself untouched.
 *
 * <p>Read {@link #get()} each time a value is needed rather than keeping the result in a field:
 * that is what makes a reload take effect.
 *
 * @param <T> the settings class
 */
public final class ConfigService<T extends ConfigRoot> implements Module {

    private static final String FILE_NAME = "config.yml";

    private final Path file;
    private final Logger logger;
    private final Supplier<T> defaults;
    private final ConfigMigrator migrator;
    private final YamlConfigurationStore<T> store;
    private volatile @Nullable T current;

    /**
     * @param type the settings class, annotated for ConfigLib
     * @param defaults the settings of a fresh install
     * @param migrations the steps that upgrade a file written by an older version, see
     *     {@link ConfigMigrator}
     * @param header the comment at the top of the file
     */
    public ConfigService(
            Path dataDirectory,
            Logger logger,
            Class<T> type,
            Supplier<T> defaults,
            List<Consumer<Map<String, Object>>> migrations,
            String header) {
        this.file = dataDirectory.resolve(FILE_NAME);
        this.logger = logger;
        this.defaults = defaults;
        this.migrator = new ConfigMigrator(migrations);
        YamlConfigurationProperties properties = YamlConfigurationProperties.newBuilder()
                .setNameFormatter(NameFormatters.LOWER_KEBAB_CASE)
                .header(header)
                .build();
        this.store = new YamlConfigurationStore<>(type, properties);
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
    public T get() {
        T config = current;
        if (config == null) {
            throw new IllegalStateException("The configuration has not been loaded yet");
        }
        return config;
    }

    /**
     * Reads, migrates, maps and validates the file, in that order, and only then touches the disk:
     * whatever goes wrong before, the administrator's file is as they left it.
     */
    private T load() throws ConfigException {
        try {
            Files.createDirectories(file.getParent());
            String onDisk = Files.exists(file) ? Files.readString(file) : null;
            Map<String, Object> content = onDisk == null ? Map.of() : Yaml.parse(onDisk);

            Optional<Map<String, Object>> migrated = migrator.migrate(content);
            Path backup = null;
            if (migrated.isPresent() && onDisk != null) {
                backup = file.resolveSibling(FILE_NAME + ".v" + content.get(ConfigMigrator.VERSION_KEY) + ".bak");
                content = migrated.get();
            }

            // An empty file is treated like a missing one: both get the defaults.
            T config = content.isEmpty()
                    ? defaults.get()
                    : store.read(new ByteArrayInputStream(Yaml.dump(content).getBytes(StandardCharsets.UTF_8)));
            if (config.configVersion() != migrator.targetVersion()) {
                // A programming error, not the administrator's: a step was added without the
                // settings class starting at the new version. A fresh install would then be written
                // at the old version and migrated, needlessly, the next time it is loaded.
                throw new IllegalStateException(config.getClass().getSimpleName() + " starts at config-version "
                        + config.configVersion() + " but the migration steps lead to version "
                        + migrator.targetVersion() + ": " + FILE_NAME + " would be written at the wrong version");
            }
            config.validate();

            if (backup != null) {
                Files.writeString(backup, onDisk);
                logger.info("{} was written by an older version: migrated, previous file kept as {}", FILE_NAME, backup.getFileName());
            }
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

    private String render(T config) {
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
