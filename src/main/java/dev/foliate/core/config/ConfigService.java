package dev.foliate.core.config;

import de.exlll.configlib.Configuration;
import de.exlll.configlib.ConfigurationException;
import de.exlll.configlib.NameFormatter;
import de.exlll.configlib.NameFormatters;
import de.exlll.configlib.SerializeWith;
import de.exlll.configlib.YamlConfigurationProperties;
import de.exlll.configlib.YamlConfigurationStore;
import dev.foliate.core.module.Module;
import dev.foliate.core.yaml.Yaml;
import dev.foliate.core.yaml.YamlException;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFileAttributes;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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

    /** How the settings are named in the file. */
    private static final NameFormatter NAMES = NameFormatters.LOWER_KEBAB_CASE;

    /** How the library names a field in its messages: {@code for field 'private int a.Settings.port'}. */
    private static final Pattern FIELD = Pattern.compile("for field '[^']* ([\\w.$]+)\\.(\\w+)'");

    private final Path file;
    private final Logger logger;
    private final Supplier<T> defaults;
    private final ConfigMigrator migrator;
    private final YamlConfigurationStore<T> store;
    private final Class<T> type;
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
                .setNameFormatter(NAMES)
                .header(header)
                .build();
        this.store = new YamlConfigurationStore<>(type, properties);
        this.type = type;
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
            requireWholeNumbers(type, content, "");
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
                writeLike(file, backup, Objects.requireNonNull(onDisk, "a backup is only made of a file"));
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
        } catch (CharacterCodingException e) {
            throw new ConfigException(FILE_NAME + " is not saved as UTF-8: save it again as UTF-8 in a text editor", e);
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
                return Objects.requireNonNullElse(explained.getMessage(), "a setting has a value it cannot take");
            }
            if (cause instanceof ConfigurationException) {
                field(cause.getMessage()).ifPresent(fields::add);
            }
        }
        if (fields.isEmpty()) {
            return "a setting has a value of the wrong kind";
        }
        String path = fields.stream()
                .map(field -> NAMES.format(field.getName()))
                .collect(Collectors.joining("."));
        Type type = fields.getLast().getGenericType();
        // Unquoted, YAML reads a number, true, or a value starting with [ or { as something else.
        return path + " must be " + kind(type) + (type == String.class ? ": put it in quotes" : "");
    }

    /**
     * ConfigLib reads {@code 1.5} into a whole-number setting as {@code 1}, without a word, and the
     * file is then written back with {@code 1}: such a value is refused here instead. It covers the
     * fields of the settings classes and of their sections ({@code @Configuration} classes, their
     * superclasses included), not the elements of a list or a map, nor a field read by a serializer
     * of its own ({@code @SerializeWith}), which decides what it accepts.
     */
    private static void requireWholeNumbers(Class<?> settings, Map<?, ?> content, String prefix) throws ConfigException {
        for (Field field : fieldsOf(settings)) {
            if (Modifier.isStatic(field.getModifiers())
                    || Modifier.isTransient(field.getModifiers())
                    || field.isAnnotationPresent(SerializeWith.class)) {
                continue;
            }
            String path = prefix + NAMES.format(field.getName());
            Object value = content.get(NAMES.format(field.getName()));
            if (kind(field.getType()).equals("a whole number") && (value instanceof Double || value instanceof Float)) {
                throw new ConfigException(path + " must be a whole number");
            }
            if (value instanceof Map<?, ?> section && field.getType().isAnnotationPresent(Configuration.class)) {
                requireWholeNumbers(field.getType(), section, path + ".");
            }
        }
    }

    private static List<Field> fieldsOf(Class<?> settings) {
        List<Field> fields = new ArrayList<>();
        for (Class<?> type = settings; type != null && type != Object.class; type = type.getSuperclass()) {
            fields.addAll(List.of(type.getDeclaredFields()));
        }
        return fields;
    }

    /** The field a message of the library is about, if it names one. */
    private static Optional<Field> field(@Nullable String message) {
        if (message == null) {
            return Optional.empty();
        }
        // The last match: the library quotes the value before naming the field, and a value could
        // contain text that looks like a field.
        Matcher matcher = FIELD.matcher(message);
        String owner = null;
        String name = null;
        while (matcher.find()) {
            owner = matcher.group(1);
            name = matcher.group(2);
        }
        if (owner == null || name == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(Class.forName(owner, false, ConfigService.class.getClassLoader()).getDeclaredField(name));
        } catch (ClassNotFoundException | NoSuchFieldException | LinkageError e) {
            return Optional.empty();
        }
    }

    /** The kind of value a setting of this type takes, as the administrator writes it. */
    private static String kind(Type type) {
        if (type instanceof ParameterizedType generic && generic.getRawType() instanceof Class<?> raw) {
            Type[] arguments = generic.getActualTypeArguments();
            if (Collection.class.isAssignableFrom(raw)) {
                return "a list (each entry: " + kind(arguments[0]) + ")";
            }
            if (Map.class.isAssignableFrom(raw)) {
                return "a section (each value: " + kind(arguments[1]) + ")";
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

    private String render(T config) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        store.write(config, out);
        return out.toString(StandardCharsets.UTF_8);
    }

    private void reportUnknownSettings(Map<String, Object> content, String rendered) throws YamlException {
        Set<String> known = settings(Yaml.parse(rendered));
        Set<String> unknown = settings(content);
        unknown.removeAll(known);
        // An empty section the plugin knows, such as 'scoreboard: {}', was filled with defaults.
        unknown.removeIf(setting -> known.stream().anyMatch(path -> path.startsWith(setting + ".")));
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
        writeLike(file, temporary, rendered);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Writes a file that stands for {@code model}, such as its replacement or its backup, with the
     * permissions of {@code model} when it exists: the file holds the database password, and one
     * that only its owner may read must not come back readable by all. The permissions are set
     * before the text is written, so the text is never readable by more people than the model's.
     * The owner and the group are copied when the server is allowed to, which it usually is not.
     * Nothing is copied where the file system has no POSIX permissions.
     */
    private static void writeLike(Path model, Path destination, String content) throws IOException {
        Files.deleteIfExists(destination);
        Files.createFile(destination);
        PosixFileAttributeView from = Files.getFileAttributeView(model, PosixFileAttributeView.class);
        PosixFileAttributeView to = Files.getFileAttributeView(destination, PosixFileAttributeView.class);
        if (from != null && to != null && Files.exists(model)) {
            PosixFileAttributes attributes = from.readAttributes();
            to.setPermissions(attributes.permissions());
            try {
                to.setOwner(attributes.owner());
                to.setGroup(attributes.group());
            } catch (IOException | SecurityException e) {
                // Only a privileged user may give a file away: it stays the server's.
            }
        }
        Files.writeString(destination, content);
    }
}
