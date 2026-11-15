package dev.foliate.core.message;

import dev.foliate.core.config.ConfigService;
import dev.foliate.core.module.Module;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.stream.Stream;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.translation.Translator;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Every text shown to a player goes through here. Messages live in {@code lang/<locale>.yml},
 * written in MiniMessage, and are rendered in the language of whoever receives them.
 *
 * <p>Lookup order for a key: the viewer's locale ({@code fr_CA}), its language ({@code fr}), the
 * configured default locale, then English. Within a locale, a key missing from the file on disk
 * falls back to the file bundled in the jar, so new messages work after an update without the
 * administrator touching anything.
 *
 * <p>Values coming from players must be passed with {@link Placeholder#unparsed} or
 * {@link Placeholder#component}, never {@link Placeholder#parsed}: parsed input would let a player
 * inject formatting and click events.
 *
 * <p>Thread-safe. A reload swaps in a complete new set of messages, or keeps the old one when a
 * file is invalid.
 */
public final class MessageService implements Module {

    /** Locales shipped in the jar under {@code lang/}. English must stay: it is the last resort. */
    private static final List<String> BUNDLED_LOCALES = List.of("en", "fr");

    private static final String PREFIX_KEY = "prefix";
    private static final String EXTENSION = ".yml";

    private final Path directory;
    private final Function<String, @Nullable InputStream> resources;
    private final ConfigService config;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final List<TagResolver> sharedResolvers = new CopyOnWriteArrayList<>();
    private final Set<String> reportedMissingKeys = ConcurrentHashMap.newKeySet();
    private volatile Catalog catalog = new Catalog(Map.of(), Locale.ENGLISH);

    /**
     * @param dataDirectory the plugin data folder; messages are read from its {@code lang} folder
     * @param resources opens a file bundled in the jar, or returns null when it does not exist
     */
    public MessageService(
            Path dataDirectory,
            Function<String, @Nullable InputStream> resources,
            ConfigService config,
            Logger logger) {
        this.directory = dataDirectory.resolve("lang");
        this.resources = resources;
        this.config = config;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "messages";
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public void enable() throws MessageException {
        catalog = load();
    }

    @Override
    public void reload() throws MessageException {
        catalog = load();
        reportedMissingKeys.clear();
    }

    /**
     * Makes a tag available in every message, for example a placeholder bridge. The resolver is
     * given the viewer as its target when the message is rendered for someone.
     */
    public void addSharedResolver(TagResolver resolver) {
        sharedResolvers.add(resolver);
    }

    public void removeSharedResolver(TagResolver resolver) {
        sharedResolvers.remove(resolver);
    }

    /** Sends a chat message to each member of the audience, in their own language. */
    public void send(Audience audience, String key, TagResolver... arguments) {
        audience.forEachAudience(viewer -> {
            Component message = render(viewer, key, arguments);
            if (!message.equals(Component.empty())) {
                viewer.sendMessage(message);
            }
        });
    }

    public void sendActionBar(Audience audience, String key, TagResolver... arguments) {
        audience.forEachAudience(viewer -> {
            Component message = render(viewer, key, arguments);
            if (!message.equals(Component.empty())) {
                viewer.sendActionBar(message);
            }
        });
    }

    public void showTitle(Audience audience, String titleKey, String subtitleKey, TagResolver... arguments) {
        audience.forEachAudience(viewer ->
                viewer.showTitle(Title.title(render(viewer, titleKey, arguments), render(viewer, subtitleKey, arguments))));
    }

    /** Renders a message for a viewer, in the viewer's language. */
    public Component render(Pointered viewer, String key, TagResolver... arguments) {
        Catalog current = catalog;
        Locale locale = viewer.getOrDefault(Identity.LOCALE, current.defaultLocale());
        return render(current, locale, viewer, key, arguments);
    }

    /** Renders a message in a given language, for text that has no single viewer. */
    public Component render(Locale locale, String key, TagResolver... arguments) {
        return render(catalog, locale, null, key, arguments);
    }

    private Component render(
            Catalog current, Locale locale, @Nullable Pointered viewer, String key, TagResolver[] arguments) {
        String template = current.find(locale, key);
        if (template == null) {
            if (reportedMissingKeys.add(key)) {
                logger.warn("Message '{}' is not defined in any language file", key);
            }
            return Component.text(key);
        }
        if (template.isEmpty()) {
            return Component.empty();
        }

        TagResolver.Builder resolver = TagResolver.builder().resolvers(arguments).resolvers(sharedResolvers);
        String prefix = current.find(locale, PREFIX_KEY);
        if (prefix != null) {
            // Parsed on purpose: the prefix is MiniMessage written by the administrator.
            resolver.resolver(Placeholder.parsed(PREFIX_KEY, prefix));
        }
        return viewer == null
                ? miniMessage.deserialize(template, resolver.build())
                : miniMessage.deserialize(template, viewer, resolver.build());
    }

    private Catalog load() throws MessageException {
        try {
            Files.createDirectories(directory);
            Map<Locale, Map<String, String>> templates = new HashMap<>();

            for (String name : BUNDLED_LOCALES) {
                String bundled = readBundled(name);
                Path file = directory.resolve(name + EXTENSION);
                if (Files.notExists(file)) {
                    Files.writeString(file, bundled);
                }
                templates.put(Translator.parseLocale(name), parse(name + EXTENSION + " (bundled)", bundled));
            }

            try (Stream<Path> files = Files.list(directory)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(EXTENSION)).toList()) {
                    String fileName = file.getFileName().toString();
                    Locale locale = Translator.parseLocale(fileName.substring(0, fileName.length() - EXTENSION.length()));
                    if (locale == null) {
                        logger.warn("Ignoring lang/{}: the file name is not a locale such as 'en' or 'pt_BR'", fileName);
                        continue;
                    }
                    templates.computeIfAbsent(locale, key -> new HashMap<>()).putAll(parse(fileName, Files.readString(file)));
                }
            }

            Locale defaultLocale = Translator.parseLocale(config.get().defaultLocale());
            if (defaultLocale == null || !templates.containsKey(defaultLocale)) {
                throw new MessageException("default-locale '" + config.get().defaultLocale()
                        + "' has no matching file in the lang folder");
            }
            templates.replaceAll((locale, messages) -> Map.copyOf(messages));
            return new Catalog(Map.copyOf(templates), defaultLocale);
        } catch (IOException e) {
            throw new MessageException("Language files could not be read: " + e.getMessage(), e);
        }
    }

    private String readBundled(String name) throws IOException {
        try (InputStream in = resources.apply("lang/" + name + EXTENSION)) {
            if (in == null) {
                throw new IOException("lang/" + name + EXTENSION + " is missing from the plugin jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Flattens a YAML file into dotted keys. A list becomes one message with a line per entry. */
    private static Map<String, String> parse(String source, String content) throws MessageException {
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.loadFromString(content);
        } catch (InvalidConfigurationException e) {
            throw new MessageException("lang/" + source + " is not valid YAML: " + e.getMessage(), e);
        }
        Map<String, String> messages = new HashMap<>();
        for (String key : yaml.getKeys(true)) {
            Object value = yaml.get(key);
            if (value instanceof List<?> lines) {
                messages.put(key, String.join("\n", lines.stream().map(String::valueOf).toList()));
            } else if (value != null && !yaml.isConfigurationSection(key)) {
                messages.put(key, String.valueOf(value));
            }
        }
        return messages;
    }

    /** An immutable set of messages, replaced as a whole on reload. */
    private record Catalog(Map<Locale, Map<String, String>> templates, Locale defaultLocale) {

        @Nullable String find(Locale locale, String key) {
            String template = find(templates.get(locale), key);
            if (template == null && !locale.getCountry().isEmpty()) {
                template = find(templates.get(Locale.of(locale.getLanguage())), key);
            }
            if (template == null) {
                template = find(templates.get(defaultLocale), key);
            }
            if (template == null) {
                template = find(templates.get(Locale.ENGLISH), key);
            }
            return template;
        }

        private static @Nullable String find(@Nullable Map<String, String> messages, String key) {
            return messages == null ? null : messages.get(key);
        }
    }
}
