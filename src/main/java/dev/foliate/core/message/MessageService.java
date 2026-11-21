package dev.foliate.core.message;

import dev.foliate.core.module.Module;
import dev.foliate.core.yaml.Yaml;
import dev.foliate.core.yaml.YamlException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.translation.Translator;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Every text shown to a player goes through here. Messages live in {@code lang/<locale>.yml},
 * written in MiniMessage, and are rendered in the language of whoever receives them.
 *
 * <p>Lookup order for a key: the viewer's locale ({@code fr_CA}), its language ({@code fr}), the
 * configured default locale, then the English text built into the jar. Within a locale, a key
 * missing from the file on disk falls back to the built-in text for that locale, so new messages
 * work after an update without the administrator touching anything.
 *
 * <p>The language files are the administrator's: each built-in one is copied once, on first start
 * or with the update that brings it. Deleting one removes that language, and adding one adds a
 * language.
 *
 * <p>Values coming from players must be passed with {@link Placeholder#unparsed} or
 * {@link Placeholder#component}, never {@link Placeholder#parsed}: parsed input would let a player
 * inject formatting and click events.
 *
 * <p>Thread-safe. A reload swaps in a complete new set of messages, or keeps the old one when a
 * file is invalid.
 */
public final class MessageService implements Module {

    /** In the lang folder: the built-in languages already copied there. */
    private static final String OFFERED_FILE = ".built-in";

    private static final String PREFIX_KEY = "prefix";
    private static final String EXTENSION = ".yml";

    /** {@code en}, {@code pt_BR}, {@code fr-CA}: what a language file may be called. */
    private static final Pattern LOCALE_NAME = Pattern.compile("[a-zA-Z]{2,3}([_-][a-zA-Z]{2,4})?");

    private final Path directory;
    private final List<String> bundledLocales;
    private final Function<String, @Nullable InputStream> resources;
    private final Supplier<String> defaultLocale;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final List<TagResolver> sharedResolvers = new CopyOnWriteArrayList<>();
    private final Set<String> reportedMissingKeys = ConcurrentHashMap.newKeySet();
    private volatile Catalog catalog = new Catalog(Map.of(), Map.of(), Locale.ENGLISH);

    /**
     * @param dataDirectory the plugin data folder; messages are read from its {@code lang} folder
     * @param bundledLocales the languages shipped in the jar under {@code lang/}, such as
     *     {@code en} for {@code lang/en.yml}; English must be one of them: it is the last resort
     * @param resources opens a file bundled in the jar, or returns null when it does not exist
     * @param defaultLocale the current {@code default-locale} setting, read at each load
     */
    public MessageService(
            Path dataDirectory,
            List<String> bundledLocales,
            Function<String, @Nullable InputStream> resources,
            Supplier<String> defaultLocale,
            Logger logger) {
        if (!bundledLocales.contains("en")) {
            throw new IllegalArgumentException("English (en) must be built in: it is the last resort for every message");
        }
        this.directory = dataDirectory.resolve("lang");
        this.bundledLocales = List.copyOf(bundledLocales);
        this.resources = resources;
        this.defaultLocale = defaultLocale;
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

    /** A broken language file is skipped at startup, so one typo does not keep the plugin down. */
    @Override
    public void enable() throws MessageException {
        catalog = load(false);
    }

    /** A broken language file refuses the reload: the administrator is there to see why. */
    @Override
    public void reload() throws MessageException {
        catalog = load(true);
        reportedMissingKeys.clear();
    }

    /**
     * Makes a tag available in every message, for example a placeholder bridge. The resolver is
     * given the viewer as its target when the message is rendered for someone. Arguments passed
     * when sending a message take precedence over shared tags of the same name.
     */
    public void addSharedResolver(TagResolver resolver) {
        sharedResolvers.add(resolver);
    }

    public void removeSharedResolver(TagResolver resolver) {
        sharedResolvers.remove(resolver);
    }

    /**
     * Sends a chat message to each member of the audience, in their own language.
     *
     * <p>Each viewer's message is rendered here, on the calling thread, one viewer after the other:
     * a shared tag such as {@code <papi>} is evaluated on this thread for every viewer, including
     * players whose region this thread does not own. Send a message that may contain such a tag
     * to a group of players from each player's own scheduler, not to the whole group at once.
     */
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

    /** Shows a title. Nothing is shown when both messages are empty. */
    public void showTitle(Audience audience, String titleKey, String subtitleKey, TagResolver... arguments) {
        audience.forEachAudience(viewer -> {
            Component title = render(viewer, titleKey, arguments);
            Component subtitle = render(viewer, subtitleKey, arguments);
            if (!title.equals(Component.empty()) || !subtitle.equals(Component.empty())) {
                viewer.showTitle(Title.title(title, subtitle));
            }
        });
    }

    /**
     * The lines a language has of its own for a message, from its file or, for a locale with a
     * country, from the file of its language; null when it has none. Never those of the default
     * language: this tells a language apart from the fallback. For the server test, which lives in
     * this package.
     */
    @Nullable List<String> ownLines(Locale locale, String key) {
        Map<String, List<String>> file = catalog.languages().get(locale);
        if (file == null && !locale.getCountry().isEmpty()) {
            file = catalog.languages().get(Locale.of(locale.getLanguage()));
        }
        return file == null ? null : file.get(key);
    }

    /** The language of the players whose own is not available. */
    Locale defaultLocale() {
        return catalog.defaultLocale();
    }

    /** Renders a message for a viewer, in the viewer's language. A list is joined with newlines. */
    public Component render(Pointered viewer, String key, TagResolver... arguments) {
        Catalog current = catalog;
        Locale locale = viewer.getOrDefault(Identity.LOCALE, current.defaultLocale());
        List<String> lines = find(current, locale, key);
        return lines == null
                ? Component.text(key)
                : deserialize(current, locale, viewer, String.join("\n", lines), arguments);
    }

    /** Renders a message in a given language, for text that has no single viewer. */
    public Component render(Locale locale, String key, TagResolver... arguments) {
        Catalog current = catalog;
        List<String> lines = find(current, locale, key);
        return lines == null
                ? Component.text(key)
                : deserialize(current, locale, null, String.join("\n", lines), arguments);
    }

    /**
     * Renders a message written as a list, one component per entry: what item lore and other
     * multi-line text need. A message written as a single text gives a single line.
     */
    public List<Component> renderLines(Pointered viewer, String key, TagResolver... arguments) {
        Catalog current = catalog;
        Locale locale = viewer.getOrDefault(Identity.LOCALE, current.defaultLocale());
        List<String> lines = find(current, locale, key);
        if (lines == null) {
            return List.of(Component.text(key));
        }
        return lines.stream()
                .map(line -> deserialize(current, locale, viewer, line, arguments))
                .toList();
    }

    /**
     * Renders MiniMessage text that does not come from a language file, such as a line written in
     * {@code config.yml}, with the same shared tags as regular messages.
     */
    public Component parse(Pointered viewer, String miniMessageText, TagResolver... arguments) {
        Catalog current = catalog;
        Locale locale = viewer.getOrDefault(Identity.LOCALE, current.defaultLocale());
        return deserialize(current, locale, viewer, miniMessageText, arguments);
    }

    private @Nullable List<String> find(Catalog current, Locale locale, String key) {
        List<String> lines = current.find(locale, key);
        if (lines == null && reportedMissingKeys.add(key)) {
            logger.warn("Message '{}' is not defined in any language file", key);
        }
        return lines;
    }

    private Component deserialize(
            Catalog current, Locale locale, @Nullable Pointered viewer, String template, TagResolver[] arguments) {
        if (template.isEmpty()) {
            return Component.empty();
        }

        // When two resolvers know the same tag the last one wins: shared tags go first, so that
        // what the caller passes for this message always takes precedence.
        TagResolver.Builder resolver = TagResolver.builder().resolvers(sharedResolvers);
        List<String> prefix = current.find(locale, PREFIX_KEY);
        if (prefix != null) {
            // The prefix is MiniMessage written by the administrator, parsed on its own and
            // inserted as a component: with the shared tags and this message's arguments, but
            // without itself, so a <prefix> inside the prefix stays text instead of recursing
            // forever. Parsed only when the message uses it.
            String prefixTemplate = String.join("\n", prefix);
            TagResolver withoutPrefix =
                    TagResolver.builder().resolvers(sharedResolvers).resolvers(arguments).build();
            resolver.tag(PREFIX_KEY, (queue, context) ->
                    Tag.selfClosingInserting(deserialize(viewer, prefixTemplate, withoutPrefix)));
        }
        resolver.resolvers(arguments);
        return deserialize(viewer, template, resolver.build());
    }

    private Component deserialize(@Nullable Pointered viewer, String template, TagResolver resolver) {
        return viewer == null
                ? miniMessage.deserialize(template, resolver)
                : miniMessage.deserialize(template, viewer, resolver);
    }

    private Catalog load(boolean strict) throws MessageException {
        try {
            Map<Locale, Map<String, List<String>>> builtIn = new HashMap<>();
            Map<String, String> bundledFiles = new HashMap<>();
            for (String name : bundledLocales) {
                String content = readBundled(name);
                bundledFiles.put(name, content);
                builtIn.put(Translator.parseLocale(name), parse(name + EXTENSION + " (built in)", content));
            }

            offerBuiltInLanguages(bundledFiles);

            Map<Locale, Map<String, List<String>>> languages = new HashMap<>();
            try (Stream<Path> files = Files.list(directory)) {
                // Sorted, so that which of two files for the same language wins does not depend on
                // the file system.
                List<Path> languageFiles = files.filter(path -> path.getFileName().toString().endsWith(EXTENSION))
                        .sorted()
                        .toList();
                Map<Locale, String> sources = new HashMap<>();
                for (Path file : languageFiles) {
                    String fileName = file.getFileName().toString();
                    String name = fileName.substring(0, fileName.length() - EXTENSION.length());
                    if (!LOCALE_NAME.matcher(name).matches()) {
                        logger.warn("Ignoring lang/{}: the file name is not a locale such as 'en' or 'pt_BR'", fileName);
                        continue;
                    }
                    if (!Files.isRegularFile(file)) {
                        logger.warn("Ignoring lang/{}: it is not a file", fileName);
                        continue;
                    }
                    Locale locale = Translator.parseLocale(name.replace('-', '_'));
                    String earlier = sources.get(locale);
                    if (earlier != null) {
                        logger.warn("Ignoring lang/{}: lang/{} is already the file of this language", fileName, earlier);
                        continue;
                    }
                    Map<String, List<String>> onDisk;
                    try {
                        onDisk = parse(fileName, read(file, fileName));
                    } catch (MessageException e) {
                        if (strict) {
                            throw e;
                        }
                        // The built-in text stands in until the file is fixed, so a typo in the
                        // default language cannot stop the server from starting.
                        Map<String, List<String>> builtInOfLocale = builtIn.get(locale);
                        if (builtInOfLocale == null) {
                            logger.error("Ignoring {}: fix the file and reload", e.getMessage());
                            continue;
                        }
                        logger.error("{}: using the built-in text of this language until the file is fixed and reloaded", e.getMessage());
                        languages.put(locale, Map.copyOf(builtInOfLocale));
                        continue;
                    }
                    // Claimed only once read: a broken file does not hide a good one for the same
                    // language, which then replaces the built-in text that stood in.
                    sources.put(locale, fileName);
                    Map<String, List<String>> messages = new HashMap<>(builtIn.getOrDefault(locale, Map.of()));
                    messages.putAll(onDisk);
                    languages.put(locale, Map.copyOf(messages));
                }
            }

            String configured = defaultLocale.get();
            Locale wanted = LOCALE_NAME.matcher(configured).matches()
                    ? Translator.parseLocale(configured.replace('-', '_'))
                    : null;
            Locale defaultLocale = wanted == null ? null : usable(wanted, languages, builtIn);
            if (defaultLocale == null) {
                String problem = "default-locale '" + configured + "' has no usable file in the lang folder";
                if (strict) {
                    throw new MessageException(problem);
                }
                // A setting written wrong must not cost the whole plugin: English is built in.
                logger.error("{}: using English until it is fixed and reloaded", problem);
                defaultLocale = Objects.requireNonNull(usable(Locale.ENGLISH, languages, builtIn), "English is built in");
            }
            return new Catalog(
                    Map.copyOf(languages),
                    Objects.requireNonNull(builtIn.get(Locale.ENGLISH), "English is built in"),
                    defaultLocale);
        } catch (IOException e) {
            throw new MessageException("Language files could not be read: " + e.getMessage(), e);
        }
    }

    /**
     * The locale to use for the configured default: it, or its language when it names a country
     * ({@code en_US} uses {@code en}), as for a player. A language built into the jar is usable
     * even when its file was deleted: its built-in text stands in, as for a file that is broken.
     *
     * @return null when neither has a file or built-in text
     */
    private @Nullable Locale usable(
            Locale wanted,
            Map<Locale, Map<String, List<String>>> languages,
            Map<Locale, Map<String, List<String>>> builtIn) {
        List<Locale> candidates = wanted.getCountry().isEmpty()
                ? List.of(wanted)
                : List.of(wanted, Locale.of(wanted.getLanguage()));
        for (Locale candidate : candidates) {
            if (languages.containsKey(candidate)) {
                return candidate;
            }
        }
        for (Locale candidate : candidates) {
            if (builtIn.containsKey(candidate)) {
                logger.warn("lang/{}{} is missing: using its built-in text", candidate, EXTENSION);
                languages.put(candidate, Map.copyOf(builtIn.get(candidate)));
                return candidate;
            }
        }
        return null;
    }

    /**
     * Copies each language built into the jar to the folder once: on the first start, and when an
     * update brings a new one. The folder is the administrator's: a built-in language whose file
     * they deleted is not copied again, which {@value #OFFERED_FILE} remembers.
     */
    private void offerBuiltInLanguages(Map<String, String> bundledFiles) {
        try {
            copyBuiltInLanguages(bundledFiles);
        } catch (IOException e) {
            // A folder the server may not write to (mounted read-only) holds what it holds.
            logger.warn("The built-in languages could not be copied to the lang folder: {}", e.toString());
        }
    }

    private void copyBuiltInLanguages(Map<String, String> bundledFiles) throws IOException {
        Files.createDirectories(directory);
        Path record = directory.resolve(OFFERED_FILE);
        Set<String> offered = new TreeSet<>();
        if (Files.exists(record)) {
            Files.readAllLines(record).stream()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                    .forEach(offered::add);
        }
        boolean changed = false;
        for (Map.Entry<String, String> bundled : new TreeMap<>(bundledFiles).entrySet()) {
            if (offered.add(bundled.getKey())) {
                changed = true;
                Path file = directory.resolve(bundled.getKey() + EXTENSION);
                if (Files.notExists(file)) {
                    Files.writeString(file, bundled.getValue());
                }
            }
        }
        if (changed) {
            List<String> lines = new ArrayList<>(List.of(
                    "# The built-in languages already copied to this folder, one per line.",
                    "# Delete a language's file to stop offering it: it is not copied again.",
                    "# Remove its line here to have it copied again."));
            lines.addAll(offered);
            Files.write(record, lines);
        }
    }

    /** Reads a language file; a file that cannot be read is handled like one that is broken. */
    private static String read(Path file, String fileName) throws MessageException {
        try {
            return Files.readString(file);
        } catch (CharacterCodingException e) {
            throw new MessageException("lang/" + fileName + " is not saved as UTF-8: save it again as UTF-8", e);
        } catch (IOException e) {
            throw new MessageException("lang/" + fileName + " could not be read (" + e.getMessage() + ")", e);
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

    /** Flattens a YAML file into dotted keys. A list is a message with one line per entry. */
    private static Map<String, List<String>> parse(String source, String content) throws MessageException {
        Map<String, Object> flat;
        try {
            flat = Yaml.flatten(Yaml.parse(content));
        } catch (YamlException e) {
            throw new MessageException("lang/" + source + " is " + e.getMessage(), e);
        }
        Map<String, List<String>> messages = new HashMap<>();
        flat.forEach((key, value) -> {
            if (value instanceof List<?> lines) {
                messages.put(key, lines.stream().map(String::valueOf).toList());
            } else if (value != null) {
                messages.put(key, List.of(String.valueOf(value)));
            }
        });
        return Map.copyOf(messages);
    }

    /**
     * An immutable set of messages, replaced as a whole on reload.
     *
     * @param languages the languages available on disk, each already completed with its built-in
     *     text
     * @param builtInEnglish the last resort, present even when the English file was deleted
     */
    private record Catalog(
            Map<Locale, Map<String, List<String>>> languages,
            Map<String, List<String>> builtInEnglish,
            Locale defaultLocale) {

        @Nullable List<String> find(Locale locale, String key) {
            List<String> lines = find(languages.get(locale), key);
            if (lines == null && !locale.getCountry().isEmpty()) {
                lines = find(languages.get(Locale.of(locale.getLanguage())), key);
            }
            if (lines == null) {
                lines = find(languages.get(defaultLocale), key);
            }
            if (lines == null) {
                lines = builtInEnglish.get(key);
            }
            return lines;
        }

        private static @Nullable List<String> find(@Nullable Map<String, List<String>> messages, String key) {
            return messages == null ? null : messages.get(key);
        }
    }
}
