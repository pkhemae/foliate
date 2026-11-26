package dev.foliate.hooks.placeholderapi.expansion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.FoliatePlugin;
import dev.foliate.core.placeholder.PlaceholderRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.slf4j.helpers.NOPLogger;

/** The expansion's answers, read from the registry, without a PlaceholderAPI server. */
class FoliateExpansionTest {

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
        PlaceholderRegistry registry = new PlaceholderRegistry(NOPLogger.NOP_LOGGER);
        registry.register("version", player -> "1.0");
        FoliateExpansion expansion = new FoliateExpansion(plugin.getPluginMeta(), registry);

        assertEquals("1.0", expansion.onRequest(null, "version"));
        assertNull(expansion.onRequest(null, "unknown"));
    }
    @Test
    void failingPlaceholderDoesNotReachPlaceholderApi() {
        PlaceholderRegistry registry = new PlaceholderRegistry(NOPLogger.NOP_LOGGER);
        registry.register("broken", player -> {
            throw new IllegalStateException("boom");
        });
        FoliateExpansion expansion = new FoliateExpansion(plugin.getPluginMeta(), registry);

        assertNull(expansion.onRequest(null, "broken"));
    }
    @Test
    void expansionIsNamedAfterThePluginAndSurvivesReloads() {
        FoliateExpansion expansion = new FoliateExpansion(plugin.getPluginMeta(), new PlaceholderRegistry(NOPLogger.NOP_LOGGER));

        assertEquals("foliate", expansion.getIdentifier());
        assertEquals(plugin.getPluginMeta().getVersion(), expansion.getVersion());
        assertTrue(!expansion.getAuthor().isBlank());
        assertTrue(expansion.persist());
    }
}
