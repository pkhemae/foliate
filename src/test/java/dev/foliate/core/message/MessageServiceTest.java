package dev.foliate.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.core.config.ConfigService;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.identity.Identity;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

class MessageServiceTest {

    private static final Map<String, String> BUNDLED = Map.of(
            "lang/en.yml",
            """
            prefix: "[P] "
            greeting: "<prefix>Hello <player>"
            only-english: "English only"
            farewell:
              short: "Bye"
            multi:
              - "one"
              - "two"
            silent: ""
            """,
            "lang/fr.yml",
            """
            prefix: "[P] "
            greeting: "<prefix>Bonjour <player>"
            """);

    @TempDir
    Path directory;

    private ConfigService config;
    private MessageService messages;

    @BeforeEach
    void setUp() throws Exception {
        config = new ConfigService(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        messages = new MessageService(directory, MessageServiceTest::bundled, config, NOPLogger.NOP_LOGGER);
        messages.enable();
    }

    @Test
    void bundledFilesAreCopiedOnFirstStart() {
        assertTrue(Files.exists(directory.resolve("lang/en.yml")));
        assertTrue(Files.exists(directory.resolve("lang/fr.yml")));
    }

    @Test
    void rendersInTheRequestedLanguage() {
        assertEquals("[P] Bonjour Alex", plain(Locale.FRENCH, "greeting", "Alex"));
    }

    @Test
    void regionalLocaleFallsBackToItsLanguage() {
        assertEquals("[P] Bonjour Alex", plain(Locale.CANADA_FRENCH, "greeting", "Alex"));
    }

    @Test
    void unknownLanguageFallsBackToTheDefaultLocale() {
        assertEquals("[P] Hello Alex", plain(Locale.JAPANESE, "greeting", "Alex"));
    }

    @Test
    void keyMissingFromALanguageFallsBackToEnglish() {
        assertEquals("English only", plain(Locale.FRENCH, "only-english", "Alex"));
    }

    @Test
    void nestedKeysAreDottedAndListsBecomeLines() {
        assertEquals("Bye", plain(Locale.ENGLISH, "farewell.short", "Alex"));
        assertEquals("one\ntwo", plain(Locale.ENGLISH, "multi", "Alex"));
    }

    @Test
    void unknownKeyRendersAsTheKeyItself() {
        assertEquals("does.not.exist", plain(Locale.ENGLISH, "does.not.exist", "Alex"));
    }

    @Test
    void fileOnDiskOverridesTheBundledTextAndKeepsTheRest() throws Exception {
        Files.writeString(directory.resolve("lang/en.yml"), "greeting: \"Hi <player>\"\n");

        messages.reload();

        assertEquals("Hi Alex", plain(Locale.ENGLISH, "greeting", "Alex"));
        assertEquals("English only", plain(Locale.ENGLISH, "only-english", "Alex"));
    }

    @Test
    void extraLanguageFileIsPickedUp() throws Exception {
        Files.writeString(directory.resolve("lang/de.yml"), "greeting: \"Hallo <player>\"\n");

        messages.reload();

        assertEquals("Hallo Alex", plain(Locale.GERMAN, "greeting", "Alex"));
    }

    @Test
    void playerInputIsNeverParsedAsFormatting() {
        Component rendered =
                messages.render(Locale.ENGLISH, "greeting", Placeholder.unparsed("player", "<red>Mallory</red>"));

        assertEquals("[P] Hello <red>Mallory</red>", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertTrue(colors(rendered).stream().noneMatch(NamedTextColor.RED::equals));
    }

    @Test
    void invalidFileOnReloadKeepsThePreviousMessages() throws Exception {
        Files.writeString(directory.resolve("lang/en.yml"), "greeting: [unclosed\n");

        assertThrows(MessageException.class, messages::reload);

        assertEquals("[P] Hello Alex", plain(Locale.ENGLISH, "greeting", "Alex"));
    }

    @Test
    void defaultLocaleWithoutAFileIsRejected() throws Exception {
        Files.writeString(directory.resolve("config.yml"), "default-locale: zz\n");
        config.reload();

        assertThrows(MessageException.class, messages::reload);
    }

    @Test
    void sendUsesEachViewersLanguageAndSkipsEmptyMessages() {
        RecordingAudience french = new RecordingAudience(Locale.FRANCE);
        RecordingAudience english = new RecordingAudience(Locale.US);
        Audience both = Audience.audience(french, english);

        messages.send(both, "greeting", Placeholder.unparsed("player", "Alex"));
        messages.send(both, "silent");

        assertEquals(List.of("[P] Bonjour Alex"), french.received);
        assertEquals(List.of("[P] Hello Alex"), english.received);
    }

    private String plain(Locale locale, String key, String player) {
        return PlainTextComponentSerializer.plainText()
                .serialize(messages.render(locale, key, Placeholder.unparsed("player", player)));
    }

    private static List<Object> colors(Component component) {
        List<Object> colors = new ArrayList<>();
        if (component.color() != null) {
            colors.add(component.color());
        }
        component.children().forEach(child -> colors.addAll(colors(child)));
        return colors;
    }

    private static InputStream bundled(String name) {
        String content = BUNDLED.get(name);
        return content == null ? null : new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static final class RecordingAudience implements Audience {

        private final List<String> received = new ArrayList<>();
        private final Pointers pointers;

        RecordingAudience(Locale locale) {
            this.pointers = Pointers.builder().withStatic(Identity.LOCALE, locale).build();
        }

        @Override
        public Pointers pointers() {
            return pointers;
        }

        @Override
        public void sendMessage(Component message) {
            received.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
    }
}
