package dev.foliate.hooks.placeholderapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.FoliatePlugin;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

/**
 * Covers what can run without a PlaceholderAPI server: the expansion's answers and the conversion
 * of values. The tag itself needs PlaceholderAPI running and is checked on a real server.
 */
class PlaceholderApiHookTest {

    private FoliatePlugin plugin;

    @BeforeEach
    void setUp() {
        MockBukkit.mock();
        plugin = MockBukkit.load(FoliatePlugin.class);
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void expansionAnswersFromTheRegistry() {
        PlaceholderRegistry registry = new PlaceholderRegistry();
        registry.register("version", player -> "1.0");
        FoliateExpansion expansion = new FoliateExpansion(plugin.getPluginMeta(), registry);

        assertEquals("1.0", expansion.onRequest(null, "version"));
        assertNull(expansion.onRequest(null, "unknown"));
    }

    @Test
    void expansionIsNamedAfterThePluginAndSurvivesReloads() {
        FoliateExpansion expansion = new FoliateExpansion(plugin.getPluginMeta(), new PlaceholderRegistry());

        assertEquals("foliate", expansion.getIdentifier());
        assertEquals(plugin.getPluginMeta().getVersion(), expansion.getVersion());
        assertTrue(!expansion.getAuthor().isBlank());
        assertTrue(expansion.persist());
    }

    @Test
    void legacyColoursAreUnderstoodInBothNotations() {
        assertEquals(NamedTextColor.RED, PlaceholderApiHook.toComponent("&cAdmin").color());
        assertEquals(NamedTextColor.RED, PlaceholderApiHook.toComponent("§cAdmin").color());
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
