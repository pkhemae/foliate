package dev.foliate.core.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.foliate.testing.RecordingLogger;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.pointer.Pointers;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import net.kyori.adventure.title.Title;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.slf4j.helpers.NOPLogger;

class MessageServiceTest {

    private static final String BUNDLED = """
            prefix: "[P] "
            greeting: "<prefix>Hello <player>"
            built-in-only: "From the jar"
            farewell:
              short: "Bye"
            multi:
              - "one"
              - "two"
            silent: ""
            """;

    @TempDir
    Path directory;

    private MessageService messages;

    @BeforeEach
    void setUp() throws Exception {
        messages = service(NOPLogger.NOP_LOGGER);
        messages.enable();
    }

    @Test
    void theBuiltInFileIsCopiedOnFirstStart() throws Exception {
        assertEquals(BUNDLED, Files.readString(file()));
    }

    /** Deleting the file is how an administrator gets the built-in one back. */
    @Test
    void aDeletedFileIsCopiedAgain() throws Exception {
        Files.writeString(file(), "greeting: \"Hi <player>\"\n");
        messages.reload();
        Files.delete(file());

        messages.reload();

        assertEquals(BUNDLED, Files.readString(file()));
        assertEquals("[P] Hello Alex", plain("greeting", "Alex"));
    }

    @Test
    void nestedKeysAreDottedAndListsBecomeLines() {
        assertEquals("Bye", plain("farewell.short", "Alex"));
        assertEquals("one\ntwo", plain("multi", "Alex"));
    }

    @Test
    void unknownKeyRendersAsTheKeyItselfAndIsReportedOnce() throws Exception {
        RecordingLogger logger = new RecordingLogger();
        MessageService fresh = service(logger);
        fresh.enable();

        fresh.render("does.not.exist");
        Component rendered = fresh.render("does.not.exist");

        assertEquals("does.not.exist", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals(
                List.of("WARN Message 'does.not.exist' is not defined in messages.yml"),
                logger.lines().stream().filter(line -> line.contains("does.not.exist")).toList());
    }

    /** The messages an update brings work without the administrator adding them to their file. */
    @Test
    void theFileOverridesTheBuiltInTextAndKeysItLacksFallBackToIt() throws Exception {
        Files.writeString(file(), "greeting: \"Hi <player>\"\n");

        messages.reload();

        assertEquals("Hi Alex", plain("greeting", "Alex"));
        assertEquals("From the jar", plain("built-in-only", "Alex"));
    }

    @Test
    void playerInputIsNeverParsedAsFormatting() {
        Component rendered = messages.render("greeting", Placeholder.unparsed("player", "<red>Mallory</red>"));

        assertEquals("[P] Hello <red>Mallory</red>", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertTrue(colors(rendered).stream().noneMatch(NamedTextColor.RED::equals));
    }

    /** A prefix that names itself used to recurse until the stack overflowed, on every message. */
    @Test
    void prefixThatUsesItselfRendersAsText() throws Exception {
        Files.writeString(file(), "prefix: \"<prefix><prefix>[P] \"\n");

        messages.reload();

        assertEquals("<prefix><prefix>[P] Hello Alex", plain("greeting", "Alex"));
    }

    @Test
    void prefixSeesTheArgumentsOfTheMessage() throws Exception {
        Files.writeString(file(), "prefix: \"[<player>] \"\n");

        messages.reload();

        assertEquals("[Alex] Hello Alex", plain("greeting", "Alex"));
    }

    /** A prefix that ends with an open colour, as many are written, colours the message after it. */
    @Test
    void colourLeftOpenByThePrefixCarriesOnIntoTheMessage() throws Exception {
        Files.writeString(file(), "prefix: \"<gray>[P] <yellow>\"\n");

        messages.reload();

        Component rendered = messages.render("greeting", Placeholder.unparsed("player", "Alex"));
        assertEquals("[P] Hello Alex", PlainTextComponentSerializer.plainText().serialize(rendered));
        assertEquals(NamedTextColor.YELLOW, colorOf(rendered, "Hello Alex", null));
    }

    @Test
    void prefixIsInsertedWhateverTheCaseOfItsTagAndWhenSelfClosing() throws Exception {
        Files.writeString(file(), "a: \"<PREFIX>x\"\nb: \"<prefix/>y\"\n");

        messages.reload();

        assertEquals("[P] x", plain("a", "Alex"));
        assertEquals("[P] y", plain("b", "Alex"));
    }

    @Test
    void escapedPrefixTagStaysText() throws Exception {
        Files.writeString(file(), "a: \"\\\\<prefix> and \\\\\\\\<prefix>\"\n");

        messages.reload();

        // An escaped tag is text; an escaped backslash is one backslash, followed by the prefix.
        assertEquals("<prefix> and \\[P] ", plain("a", "Alex"));
    }

    /** What the code passes is never searched for the prefix: a parsed argument cannot make it recurse. */
    @Test
    void prefixTagInAParsedArgumentStaysText() {
        Component rendered = messages.render("greeting", Placeholder.parsed("player", "<prefix>"));

        assertEquals("[P] Hello <prefix>", PlainTextComponentSerializer.plainText().serialize(rendered));
    }

    @Test
    void invalidFileOnReloadKeepsThePreviousMessages() throws Exception {
        Files.writeString(file(), "greeting: \"Hi <player>\"\n");
        messages.reload();
        Files.writeString(file(), "greeting: [unclosed\n");

        assertThrows(MessageException.class, messages::reload);

        assertEquals("Hi Alex", plain("greeting", "Alex"));
    }

    /** At startup the built-in text stands in, so one typo does not keep the plugin down. */
    @Test
    void brokenFileIsReplacedByTheBuiltInTextAtStartupButRefusedOnReload() throws Exception {
        Files.writeString(file(), "greeting: [unclosed\n");
        RecordingLogger logger = new RecordingLogger();
        MessageService fresh = service(logger);

        fresh.enable();

        assertEquals("[P] Hello Alex", PlainTextComponentSerializer.plainText()
                .serialize(fresh.render("greeting", Placeholder.unparsed("player", "Alex"))));
        assertTrue(logger.lines().stream().anyMatch(line -> line.startsWith("ERROR messages.yml is ")), logger.lines().toString());
        assertThrows(MessageException.class, fresh::reload);
    }

    /** It used to overflow the stack, which no part of the plugin catches. */
    @Test
    void fileThatContainsItselfIsReportedOnReload() throws Exception {
        Files.writeString(file(), "greeting: &x {b: *x}\n");

        MessageException failure = assertThrows(MessageException.class, messages::reload);

        assertEquals("messages.yml is not valid YAML (a value contains itself: an alias * inside the & it names,"
                + " line 1, column 18)", failure.getMessage());
    }

    /** The message reaches whoever ran /foliate reload: it names the file, not the server's folders. */
    @Test
    void fileThatCannotBeReadIsReportedWithoutItsPath() throws Exception {
        assumeTrue(FileSystems.getDefault().supportedFileAttributeViews().contains("posix"), "POSIX permissions only");
        Files.setPosixFilePermissions(file(), PosixFilePermissions.fromString("---------"));
        assumeFalse(Files.isReadable(file()), "running as a user who may read any file");

        MessageException failure = assertThrows(MessageException.class, messages::reload);

        assertEquals("messages.yml could not be read (permission denied)", failure.getMessage());
    }

    /** A folder named like the file is not one: the built-in text stands in, and a reload says why. */
    @Test
    void folderNamedLikeTheFileIsNotRead() throws Exception {
        Files.delete(file());
        Files.createDirectory(file());
        MessageService fresh = service(NOPLogger.NOP_LOGGER);

        fresh.enable();

        assertEquals("[P] Hello Alex", PlainTextComponentSerializer.plainText()
                .serialize(fresh.render("greeting", Placeholder.unparsed("player", "Alex"))));
        assertThrows(MessageException.class, fresh::reload);
    }

    /** A folder the server may not write to (mounted read-only) holds what it holds: it starts. */
    @Test
    void aFolderThatCannotBeWrittenStillStarts(@TempDir Path readOnly) throws Exception {
        java.io.File folder = readOnly.toFile();
        assertTrue(folder.setWritable(false));
        try {
            MessageService fresh = new MessageService(readOnly, MessageServiceTest::bundled, NOPLogger.NOP_LOGGER);

            fresh.enable();

            assertEquals("[P] Hello Alex", PlainTextComponentSerializer.plainText()
                    .serialize(fresh.render("greeting", Placeholder.unparsed("player", "Alex"))));
        } finally {
            folder.setWritable(true);
        }
    }

    @Test
    void aJarWithoutTheFileIsReported(@TempDir Path elsewhere) {
        MessageService broken = new MessageService(elsewhere, name -> null, NOPLogger.NOP_LOGGER);

        MessageException failure = assertThrows(MessageException.class, broken::enable);

        assertEquals("messages.yml is missing from the plugin jar", failure.getMessage());
    }

    @Test
    void keysNamedLikeYamlBooleansAreKept() throws Exception {
        Files.writeString(file(), "toggle:\n  on: \"Enabled\"\n  off: \"Disabled\"\n  yes: no\n");

        messages.reload();

        assertEquals("Enabled", plain("toggle.on", "Alex"));
        assertEquals("Disabled", plain("toggle.off", "Alex"));
        assertEquals("no", plain("toggle.yes", "Alex"));
    }

    @Test
    void argumentsTakePrecedenceOverSharedTags() {
        messages.addSharedResolver(Placeholder.unparsed("player", "SHARED"));
        messages.addSharedResolver(Placeholder.unparsed("server", "lobby"));

        assertEquals("[P] Hello Alex", plain("greeting", "Alex"));
    }

    @Test
    void sharedTagsAreAvailableInEveryMessage() throws Exception {
        Files.writeString(file(), "where: \"On <server>\"\n");
        messages.reload();
        messages.addSharedResolver(Placeholder.unparsed("server", "lobby"));

        assertEquals("On lobby", plain("where", "Alex"));
    }

    @Test
    void listMessageCanBeRenderedLineByLine() {
        List<String> lines = messages.renderLines(new RecordingAudience(), "multi").stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .toList();

        assertEquals(List.of("one", "two"), lines);
    }

    /** An action bar is rendered for the viewer; an empty message shows nothing. */
    @Test
    void actionBarIsRenderedForTheViewer() {
        PlayerMock player = MockBukkit.mock().addPlayer("Alex");
        try {
            messages.sendActionBar(player, "farewell.short");
            messages.sendActionBar(player, "silent");

            assertEquals("Bye", PlainTextComponentSerializer.plainText().serialize(player.nextActionBar()));
            assertEquals(null, player.nextActionBar(), "an empty message was shown");
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test
    void titleIsNotShownWhenBothPartsAreEmpty() {
        RecordingAudience viewer = new RecordingAudience();

        messages.showTitle(viewer, "silent", "silent");
        messages.showTitle(viewer, "farewell.short", "silent");

        assertEquals(1, viewer.titles);
    }

    @Test
    void sendReachesEveryViewerAndSkipsEmptyMessages() {
        RecordingAudience first = new RecordingAudience();
        RecordingAudience second = new RecordingAudience();
        Audience both = Audience.audience(first, second);

        messages.send(both, "greeting", Placeholder.unparsed("player", "Alex"));
        messages.send(both, "silent");

        assertEquals(List.of("[P] Hello Alex"), first.received);
        assertEquals(List.of("[P] Hello Alex"), second.received);
    }

    private MessageService service(org.slf4j.Logger logger) {
        return new MessageService(directory, MessageServiceTest::bundled, logger);
    }

    private Path file() {
        return directory.resolve(MessageService.FILE);
    }

    private String plain(String key, String player) {
        return PlainTextComponentSerializer.plainText()
                .serialize(messages.render(key, Placeholder.unparsed("player", player)));
    }

    private static List<Object> colors(Component component) {
        List<Object> colors = new ArrayList<>();
        if (component.color() != null) {
            colors.add(component.color());
        }
        component.children().forEach(child -> colors.addAll(colors(child)));
        return colors;
    }

    /** The colour a piece of text is shown in, inherited from the components around it. */
    private static @Nullable TextColor colorOf(Component component, String text, @Nullable TextColor inherited) {
        TextColor color = component.color() != null ? component.color() : inherited;
        if (component instanceof net.kyori.adventure.text.TextComponent part && part.content().contains(text)) {
            return color;
        }
        for (Component child : component.children()) {
            TextColor found = colorOf(child, text, color);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static @Nullable InputStream bundled(String name) {
        return MessageService.FILE.equals(name)
                ? new ByteArrayInputStream(BUNDLED.getBytes(StandardCharsets.UTF_8))
                : null;
    }

    private static final class RecordingAudience implements Audience {

        private final List<String> received = new ArrayList<>();
        private int titles;

        @Override
        public void showTitle(Title title) {
            titles++;
        }

        @Override
        public Pointers pointers() {
            return Pointers.empty();
        }

        @Override
        public void sendMessage(Component message) {
            received.add(PlainTextComponentSerializer.plainText().serialize(message));
        }
    }
}
