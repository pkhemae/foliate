package dev.foliate.hooks.placeholderapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Covers what can run without a PlaceholderAPI server: the conversion of values. The expansion is
 * covered by {@code FoliateExpansionTest}. The tag itself needs PlaceholderAPI running and is checked on a real server.
 */
class PlaceholderApiHookTest {

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void legacyColoursAreUnderstoodInBothNotations() {
        assertEquals(NamedTextColor.RED, PlaceholderApiHook.toComponent("&cAdmin").color());
        assertEquals(NamedTextColor.RED, PlaceholderApiHook.toComponent("§cAdmin").color());
    }

    @Test
    void hexadecimalColoursAreUnderstoodInBothNotations() {
        assertEquals(TextColor.color(0x12AB34), PlaceholderApiHook.toComponent("&#12AB34Gold").color());
        assertEquals(TextColor.color(0x12AB34), PlaceholderApiHook.toComponent("\u00A7x\u00A71\u00A72\u00A7a\u00A7b\u00A73\u00A74Gold").color());
    }

    /** The price of honouring & codes, as Bukkit's translateAlternateColorCodes does: &d is a code. */
    @Test
    void ampersandFollowedByACodeCharacterIsACode() {
        assertEquals("R", PlainTextComponentSerializer.plainText().serialize(PlaceholderApiHook.toComponent("R&D")));
    }

    /** Only an & followed by a colour or format character is a code: the convention of Bukkit. */
    @Test
    void ampersandThatIsNotACodeStaysText() {
        assertEquals("Tom & Jerry", PlainTextComponentSerializer.plainText().serialize(PlaceholderApiHook.toComponent("Tom & Jerry")));
    }

    @Test
    void valueIsNeverParsedAsMiniMessage() {
        Component component = PlaceholderApiHook.toComponent("<click:run_command:/op me>click</click>");

        assertEquals(
                "<click:run_command:/op me>click</click>",
                PlainTextComponentSerializer.plainText().serialize(component));
        ClickEvent<?> click = component.clickEvent();
        assertNull(click);
    }
}
