package dev.foliate.core.message;

import dev.foliate.core.io.FileErrors;
import dev.foliate.core.module.Module;
import dev.foliate.core.yaml.Yaml;
import dev.foliate.core.yaml.YamlException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.pointer.Pointered;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Every text shown to a player goes through here. Messages live in {@value #FILE}, in the plugin
 * folder, written in MiniMessage: the plugin speaks one language, the one of that file. It ships in
 * English; an administrator translates it by editing the file.
 *
 * <p>The file is the administrator's. It is copied from the jar when it is missing: on the first
 * start, or after it was deleted, which resets it. A key missing from it falls back to the text
 * built into the jar, so the messages an update brings work without the administrator touching
 * anything.
 *
 * <p>Values coming from players must be passed with {@link Placeholder#unparsed} or
 * {@link Placeholder#component}, never {@link Placeholder#parsed}: parsed input would let a player
 * inject formatting and click events.
 *
 * <p>Thread-safe. A reload swaps in a complete new set of messages, or keeps the old one when the
 * file is invalid.
 */
public final class MessageService implements Module {

    /** The file of the messages, in the plugin folder and in the jar. */
    public static final String FILE = "messages.yml";

    private static final String PREFIX_KEY = "prefix";

    /** How a message asks for the prefix. MiniMessage tag names ignore case. */
    private static final List<String> PREFIX_TAGS = List.of("<" + PREFIX_KEY + ">", "<" + PREFIX_KEY + "/>");

    private final Path file;
    private final Function<String, @Nullable InputStream> resources;
    private final Logger logger;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final List<TagResolver> sharedResolvers = new CopyOnWriteArrayList<>();
    private final Set<String> reportedMissingKeys = ConcurrentHashMap.newKeySet();
    private volatile Map<String, List<String>> messages = Map.of();

    /**
     * @param dataDirectory the plugin data folder, which holds {@value #FILE}
     * @param resources opens a file bundled in the jar, or returns null when it does not exist
     */
    public MessageService(Path dataDirectory, Function<String, @Nullable InputStream> resources, Logger logger) {
        this.file = dataDirectory.resolve(FILE);
        this.resources = resources;
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

    /** A broken file is replaced by the built-in text at startup, so one typo does not keep the plugin down. */
    @Override
    public void enable() throws MessageException {
        messages = load(false);
    }

    /** A broken file refuses the reload: the administrator is there to see why. */
    @Override
    public void reload() throws MessageException {
        messages = load(true);
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
     * Sends a chat message to each member of the audience.
     *
     * <p>Each viewer's message is rendered here, on the calling thread, one viewer after the other,
     * since a shared tag may depend on who reads it: a tag such as {@code <papi>} is evaluated on
     * this thread for every viewer, including players whose region this thread does not own. Send
     * a message that may contain such a tag to a group of players from each player's own
     * scheduler, not to the whole group at once.
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
     * Renders a message for a viewer, whom the shared tags are given as their target. A list is
     * joined with newlines.
     */
    public Component render(Pointered viewer, String key, TagResolver... arguments) {
        Map<String, List<String>> current = messages;
        List<String> lines = find(current, key);
        return lines == null ? Component.text(key) : deserialize(current, viewer, String.join("\n", lines), arguments);
    }

    /** Renders a message for no one in particular, such as a kick message written before the player exists. */
    public Component render(String key, TagResolver... arguments) {
        Map<String, List<String>> current = messages;
        List<String> lines = find(current, key);
        return lines == null ? Component.text(key) : deserialize(current, null, String.join("\n", lines), arguments);
    }

    /**
     * Renders a message written as a list, one component per entry: what item lore and other
     * multi-line text need. A message written as a single text gives a single line.
     */
    public List<Component> renderLines(Pointered viewer, String key, TagResolver... arguments) {
        Map<String, List<String>> current = messages;
        List<String> lines = find(current, key);
        if (lines == null) {
            return List.of(Component.text(key));
        }
        return lines.stream().map(line -> deserialize(current, viewer, line, arguments)).toList();
    }

    /**
     * Renders MiniMessage text that does not come from {@value #FILE}, such as a line written in
     * {@code config.yml}, with the same shared tags and prefix as regular messages.
     */
    public Component parse(Pointered viewer, String miniMessageText, TagResolver... arguments) {
        return deserialize(messages, viewer, miniMessageText, arguments);
    }

    private @Nullable List<String> find(Map<String, List<String>> current, String key) {
        List<String> lines = current.get(key);
        if (lines == null && reportedMissingKeys.add(key)) {
            logger.warn("Message '{}' is not defined in {}", key, FILE);
        }
        return lines;
    }

    /** Renders with the message and the prefix of one load, so a reload in between cannot mix two. */
    private Component deserialize(
            Map<String, List<String>> current, @Nullable Pointered viewer, String template, TagResolver[] arguments) {
        if (template.isEmpty()) {
            return Component.empty();
        }

        // When two resolvers know the same tag the last one wins: shared tags go first, so that
        // what the caller passes for this message always takes precedence.
        TagResolver resolver = TagResolver.builder().resolvers(sharedResolvers).resolvers(arguments).build();
        List<String> prefix = current.get(PREFIX_KEY);
        String text = prefix == null ? template : insertPrefix(template, String.join("\n", prefix));
        return deserialize(viewer, text, resolver);
    }

    /**
     * Puts the prefix's own text in place of each {@code <prefix>} tag of a message, before the
     * message is parsed, as if the administrator had written it there: a colour the prefix leaves
     * open carries on into the message, and the prefix sees the shared tags and the message's
     * arguments. Done in one pass that does not look at what it inserts, and with no tag named
     * {@code prefix} when parsing: a {@code <prefix>} inside the prefix, or in a value passed with
     * {@link Placeholder#parsed}, is shown as text instead of recursing forever.
     *
     * <p>Only the message, written by the administrator, is searched: values passed by the code
     * are resolved by MiniMessage afterwards and never pass through here, so a player cannot
     * inject anything by typing {@code <prefix>}. A tag escaped as {@code \<prefix>} stays text.
     * The tag is found wherever it is written, inside the quoted argument of another tag too
     * ({@code <hover:show_text:'<prefix>'>}), where a prefix holding that same quote would end the
     * argument early.
     */
    private static String insertPrefix(String template, String prefix) {
        StringBuilder text = new StringBuilder(template.length() + prefix.length());
        int backslashes = 0;
        int i = 0;
        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '<' && backslashes % 2 == 0) {
                int length = prefixTagLength(template, i);
                if (length > 0) {
                    text.append(prefix);
                    i += length;
                    backslashes = 0;
                    continue;
                }
            }
            backslashes = c == '\\' ? backslashes + 1 : 0;
            text.append(c);
            i++;
        }
        return text.toString();
    }

    /** The length of {@code <prefix>} or {@code <prefix/>}, in any case, at that index; 0 if absent. */
    private static int prefixTagLength(String template, int index) {
        for (String tag : PREFIX_TAGS) {
            if (template.regionMatches(true, index, tag, 0, tag.length())) {
                return tag.length();
            }
        }
        return 0;
    }

    private Component deserialize(@Nullable Pointered viewer, String template, TagResolver resolver) {
        return viewer == null
                ? miniMessage.deserialize(template, resolver)
                : miniMessage.deserialize(template, viewer, resolver);
    }

    private Map<String, List<String>> load(boolean strict) throws MessageException {
        String bundled = readBundled();
        Map<String, List<String>> builtIn = parse(FILE + " (built in)", bundled);
        Map<String, List<String>> onDisk;
        try {
            String content = read();
            if (content == null) {
                copyBuiltIn(bundled);
                return builtIn;
            }
            onDisk = parse(FILE, content);
        } catch (MessageException e) {
            if (strict) {
                throw e;
            }
            // The built-in text stands in until the file is fixed, so a typo cannot stop the
            // server from starting.
            logger.error("{}: using the built-in messages until the file is fixed and reloaded", e.getMessage());
            return builtIn;
        }
        Map<String, List<String>> merged = new HashMap<>(builtIn);
        merged.putAll(onDisk);
        return Map.copyOf(merged);
    }

    /**
     * Puts the built-in file in the folder, on the first start or after it was deleted: through
     * a symbolic link whose target does not exist yet, too.
     */
    private void copyBuiltIn(String bundled) {
        try {
            Path folder = file.getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            Files.writeString(file, bundled);
        } catch (IOException e) {
            // A folder the server may not write to (mounted read-only) holds what it holds.
            logger.warn("{} could not be written ({}): using the built-in messages", FILE, FileErrors.reason(e));
        }
    }

    /**
     * Reads the file; null when there is none. A file that cannot be read is handled like one that
     * is broken: a folder in its place, a link to where the server may not look, a lost permission.
     */
    private @Nullable String read() throws MessageException {
        try {
            return Files.readString(file);
        } catch (NoSuchFileException e) {
            return null;
        } catch (CharacterCodingException e) {
            throw new MessageException(FILE + " is not saved as UTF-8: save it again as UTF-8", e);
        } catch (IOException e) {
            throw new MessageException(FILE + " could not be read (" + FileErrors.reason(e) + ")", e);
        }
    }

    private String readBundled() throws MessageException {
        try (InputStream in = resources.apply(FILE)) {
            if (in == null) {
                throw new MessageException(FILE + " is missing from the plugin jar");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MessageException(FILE + " could not be read from the plugin jar", e);
        }
    }

    /** Flattens a YAML file into dotted keys. A list is a message with one line per entry. */
    private static Map<String, List<String>> parse(String source, String content) throws MessageException {
        Map<String, Object> flat;
        try {
            flat = Yaml.flatten(Yaml.parse(content));
        } catch (YamlException e) {
            throw new MessageException(source + " is " + e.getMessage(), e);
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
}
