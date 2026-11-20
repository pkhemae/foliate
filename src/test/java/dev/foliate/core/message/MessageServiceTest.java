package dev.foliate.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
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
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
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

    private ConfigService<PluginConfig> config;
    private MessageService messages;

    @BeforeEach
    void setUp() throws Exception {
        config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        messages = new MessageService(directory, MessageServiceTest::bundled, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);
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
        assertEquals("[P] Hello Alex", plain(Locale.ENGLISH, "greeting", "Alex"));
    }

    @Test
    void defaultLocaleIsUsedBeforeEnglish() throws Exception {
        useDefaultLocale("fr");

        assertEquals("[P] Bonjour Alex", plain(Locale.JAPANESE, "greeting", "Alex"));
        // French has no such key: English built-in text is the last resort.
        assertEquals("English only", plain(Locale.JAPANESE, "only-english", "Alex"));
    }

    @Test
    void viewerWithoutALocaleGetsTheDefaultLocale() throws Exception {
        useDefaultLocale("fr");
        RecordingAudience console = new RecordingAudience(null);

        messages.send(console, "greeting", Placeholder.unparsed("player", "Alex"));

        assertEquals(List.of("[P] Bonjour Alex"), console.received);
    }

    @Test
    void filesThatAreNotNamedAfterALocaleAreIgnored() throws Exception {
        Files.writeString(directory.resolve("lang/backup.yml"), "greeting: \"from backup\"\n");
        Files.writeString(directory.resolve("lang/en - Copy.yml"), "greeting: \"from copy\"\n");

        messages.reload();

        assertEquals("[P] Hello Alex", plain(Locale.of("backup"), "greeting", "Alex"));
    }

    @Test
    void hyphenatedFileNameIsARegionalLocale() throws Exception {
        Files.writeString(directory.resolve("lang/fr-CA.yml"), "greeting: \"Allo <player>\"\n");

        messages.reload();

        assertEquals("Allo Alex", plain(Locale.CANADA_FRENCH, "greeting", "Alex"));
        assertEquals("[P] Bonjour Alex", plain(Locale.FRANCE, "greeting", "Alex"));
    }

    @Test
    void keysNamedLikeYamlBooleansAreKept() throws Exception {
        Files.writeString(directory.resolve("lang/en.yml"), "toggle:\n  on: \"Enabled\"\n  off: \"Disabled\"\n  yes: no\n");

        messages.reload();

        assertEquals("Enabled", plain(Locale.ENGLISH, "toggle.on", "Alex"));
        assertEquals("Disabled", plain(Locale.ENGLISH, "toggle.off", "Alex"));
        assertEquals("no", plain(Locale.ENGLISH, "toggle.yes", "Alex"));
    }

    @Test
    void argumentsTakePrecedenceOverSharedTags() {
        messages.addSharedResolver(Placeholder.unparsed("player", "SHARED"));
        messages.addSharedResolver(Placeholder.unparsed("server", "lobby"));

        assertEquals("[P] Hello Alex", plain(Locale.ENGLISH, "greeting", "Alex"));
    }

    @Test
    void sharedTagsAreAvailableInEveryMessage() throws Exception {
        Files.writeString(directory.resolve("lang/en.yml"), "where: \"On <server>\"\n");
        messages.reload();
        messages.addSharedResolver(Placeholder.unparsed("server", "lobby"));

        assertEquals("On lobby", plain(Locale.ENGLISH, "where", "Alex"));
    }

    @Test
    void listMessageCanBeRenderedLineByLine() {
        RecordingAudience viewer = new RecordingAudience(Locale.US);

        List<String> lines = messages.renderLines(viewer, "multi").stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();

        assertEquals(List.of("one", "two"), lines);
    }

    @Test
    void deletedLanguageStaysDeletedAndItsSpeakersGetTheDefault() throws Exception {
        Files.delete(directory.resolve("lang/fr.yml"));

        messages.reload();

        assertFalse(Files.exists(directory.resolve("lang/fr.yml")));
        assertEquals("[P] Hello Alex", plain(Locale.FRENCH, "greeting", "Alex"));
    }

    @Test
    void brokenExtraLanguageIsSkippedAtStartupButRefusedOnReload() throws Exception {
        Files.writeString(directory.resolve("lang/de.yml"), "greeting: [unclosed\n");
        MessageService fresh = new MessageService(directory, MessageServiceTest::bundled, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);

        fresh.enable();

        assertEquals("[P] Hello Alex", PlainTextComponentSerializer.plainText()
                .serialize(fresh.render(Locale.GERMAN, "greeting", Placeholder.unparsed("player", "Alex"))));
        assertThrows(MessageException.class, fresh::reload);
    }

    @Test
    void brokenFileOfTheDefaultLocaleDoesNotStopTheServerFromStarting() throws Exception {
        Files.writeString(directory.resolve("lang/en.yml"), "greeting: [unclosed\n");
        MessageService fresh = new MessageService(directory, MessageServiceTest::bundled, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);

        fresh.enable();

        // The built-in text stands in for the file until it is fixed.
        assertEquals("[P] Hello Alex", PlainTextComponentSerializer.plainText()
                .serialize(fresh.render(Locale.ENGLISH, "greeting", Placeholder.unparsed("player", "Alex"))));
        assertThrows(MessageException.class, fresh::reload);
    }

    @Test
    void brokenFileOfAnotherBundledLocaleUsedAsDefaultFallsBackToo() throws Exception {
        Files.writeString(directory.resolve("lang/fr.yml"), "greeting: [unclosed\n");
        Files.writeString(directory.resolve("config.yml"), "default-locale: fr\n");
        config.reload();
        MessageService fresh = new MessageService(directory, MessageServiceTest::bundled, () -> config.get().defaultLocale(), NOPLogger.NOP_LOGGER);

        fresh.enable();

        assertEquals("[P] Bonjour Alex", PlainTextComponentSerializer.plainText()
                .serialize(fresh.render(Locale.JAPANESE, "greeting", Placeholder.unparsed("player", "Alex"))));
    }

    @Test
    void deletedFileOfTheDefaultLocaleFallsBackToItsBuiltInText() throws Exception {
        Files.delete(directory.resolve("lang/en.yml"));

        messages.reload();

        assertEquals("[P] Hello Alex", plain(Locale.JAPANESE, "greeting", "Alex"));
    }

    @Test
    void titleIsNotShownWhenBothPartsAreEmpty() {
        RecordingAudience viewer = new RecordingAudience(Locale.US);

        messages.showTitle(viewer, "silent", "silent");
        messages.showTitle(viewer, "farewell.short", "silent");

        assertEquals(1, viewer.titles);
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

    /**
     * MockBukkit's players keep one locale in their pointers whatever {@code setLocale} says,
     * which is what a server that cannot read its players' language would look like. The real
     * thing is checked with a connected client in CI.
     */
    @Test
    void playerWhoseLanguageCannotBeReadIsReported() throws Exception {
        Files.writeString(directory.resolve("lang/en.yml"), "command:\n  reload:\n    success: \"Not now\"\n");
        Files.writeString(directory.resolve("lang/fr.yml"), "command:\n  reload:\n    success: \"Pas maintenant\"\n");
        messages.reload();
        PlayerMock player = MockBukkit.mock().addPlayer("Alex");
        try {
            player.setLocale(Locale.FRENCH);

            assertEquals(
                    "Alex is not spoken to in fr: got the default language's \"Not now\"",
                    messages.problemWith(player, 1));
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void playerWhoseLanguageIsTheDefaultOneIsFine() throws Exception {
        PlayerMock player = MockBukkit.mock().addPlayer("Alex");
        try {
            player.setLocale(Locale.ENGLISH);

            assertEquals(null, messages.problemWith(player, 1));
        } finally {
            MockBukkit.unmock();
        }
    }

    private void useDefaultLocale(String locale) throws Exception {
        Files.writeString(directory.resolve("config.yml"), "default-locale: " + locale + "\n");
        config.reload();
        messages.reload();
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
        private int titles;

        /** @param locale null for a viewer that has none, like the console */
        RecordingAudience(Locale locale) {
            this.pointers = locale == null
                    ? Pointers.empty()
                    : Pointers.builder().withStatic(Identity.LOCALE, locale).build();
        }

        @Override
        public void showTitle(Title title) {
            titles++;
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
