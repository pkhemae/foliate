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
import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
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

    /** How the library names a field in its messages: {@code for field 'private int a.Settings.port'}. */
    private static final Pattern FIELD = Pattern.compile("for field '[^']* ([\\w.$]+)\\.(\\w+)'");

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
            // Not chained: the library's messages print the values of the whole section, password
            // included, and a stack trace would print every cause.
            throw new ConfigException(FILE_NAME + " is invalid: " + describe(e));
        } catch (IOException | UncheckedIOException e) {
            throw new ConfigException(FILE_NAME + " could not be read or written: " + e.getMessage(), e);
        }
    }

    /**
     * What is wrong, in the administrator's terms: the setting's path and the kind of value it
     * takes, such as {@code storage.mysql.port must be a whole number}.
     *
     * <p>Built from the fields the library names, never from its text: that text quotes the value,
     * which may be a password, in a different way for every kind of mistake, so no filter on it
     * can be trusted. A serializer of the plugin explains itself with an
     * {@link InvalidSettingException}, whose message is shown as it is.
     */
    static String describe(ConfigurationException failure) {
        List<Field> fields = new ArrayList<>();
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidSettingException explained) {
                return explained.getMessage();
            }
            if (cause instanceof ConfigurationException) {
                field(cause.getMessage()).ifPresent(fields::add);
            }
        }
        if (fields.isEmpty()) {
            return "a setting has a value of the wrong kind";
        }
        String path = fields.stream()
                .map(field -> NameFormatters.LOWER_KEBAB_CASE.format(field.getName()))
                .collect(Collectors.joining("."));
        return path + " must be " + kind(fields.getLast().getGenericType());
    }

    /** The field a message of the library is about, if it names one. */
    private static Optional<Field> field(@Nullable String message) {
        Matcher matcher = message == null ? null : FIELD.matcher(message);
        if (matcher == null || !matcher.find()) {
            return Optional.empty();
        }
        try {
            Class<?> owner = Class.forName(matcher.group(1), false, ConfigService.class.getClassLoader());
            return Optional.of(owner.getDeclaredField(matcher.group(2)));
        } catch (ClassNotFoundException | NoSuchFieldException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** The kind of value a setting of this type takes, as the administrator writes it. */
    private static String kind(Type type) {
        if (type instanceof ParameterizedType generic && generic.getRawType() instanceof Class<?> raw) {
            if (Collection.class.isAssignableFrom(raw)) {
                return "a list of " + plural(kind(generic.getActualTypeArguments()[0]));
            }
            return kind(raw);
        }
        if (!(type instanceof Class<?> raw)) {
            return "a value of another kind";
        }
        if (raw == String.class) {
            return "text";
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return "true or false";
        }
        if (raw == int.class || raw == long.class || raw == short.class || raw == byte.class
                || raw == Integer.class || raw == Long.class || raw == Short.class || raw == Byte.class) {
            return "a whole number";
        }
        if (raw == double.class || raw == float.class || raw == Double.class || raw == Float.class) {
            return "a number";
        }
        if (raw.isEnum()) {
            return "one of " + Arrays.stream(raw.getEnumConstants())
                    .map(constant -> ((Enum<?>) constant).name())
                    .collect(Collectors.joining(", "));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            return "a list";
        }
        return "a section of settings";
    }

    private static String plural(String kind) {
        return switch (kind) {
            case "text" -> "texts";
            case "a whole number" -> "whole numbers";
            case "a number" -> "numbers";
            default -> kind.startsWith("a ") ? kind.substring(2) + "s" : kind;
        };
    }

    private String render(T config) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.write(config, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private void reportUnknownSettings(Map<String, Object> content, String rendered) throws YamlException {
        Set<String> unknown = settings(content);
        unknown.removeAll(settings(Yaml.parse(rendered)));
        // A whole unknown section shows up as its leaves; a known list shows up as one key.
        for (String setting : unknown) {
            logger.warn("{}: '{}' is not a setting of this plugin and was removed from the file", FILE_NAME, setting);
        }
    }

    /** The dotted paths of the file's values, an empty section counting as one. */
    private static Set<String> settings(Map<?, ?> content) {
        Set<String> paths = new TreeSet<>();
        collect("", content, paths);
        return paths;
    }

    private static void collect(String prefix, Map<?, ?> section, Set<String> paths) {
        if (section.isEmpty() && !prefix.isEmpty()) {
            paths.add(prefix);
        }
        section.forEach((key, value) -> {
            String path = prefix.isEmpty() ? String.valueOf(key) : prefix + "." + key;
            if (value instanceof Map<?, ?> nested) {
                collect(path, nested, paths);
            } else {
                paths.add(path);
            }
        });
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
