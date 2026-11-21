package dev.foliate;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

class FoliatePluginTest {

    private ServerMock server;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void pluginEnables() {
        FoliatePlugin plugin = MockBukkit.load(FoliatePlugin.class);

        assertTrue(plugin.isEnabled());
        assertTrue(Files.exists(plugin.getDataPath().resolve("config.yml")));
        assertTrue(Files.exists(plugin.getDataPath().resolve("data.db")), "the database did not start");
    }

    /** A required module that fails, here the configuration, stops the plugin instead of half of it running. */
    @Test
    void pluginWithAnInvalidConfigurationDisablesItself() throws Exception {
        FoliatePlugin plugin = MockBukkit.load(FoliatePlugin.class);
        server.getPluginManager().disablePlugin(plugin);
        Files.writeString(plugin.getDataPath().resolve("config.yml"), "storage: [not, a, section]\n");

        server.getPluginManager().enablePlugin(plugin);

        assertFalse(plugin.isEnabled());
    }

    /**
     * The shutdown order: the players still online are saved, their quit applied, before the
     * database closes. The join saved the row already: only the time they were last seen tells
     * the shutdown's save from it.
     */
    @Test
    void pluginSavesThePlayersStillOnlineWhenItIsDisabled() throws Exception {
        FoliatePlugin plugin = MockBukkit.load(FoliatePlugin.class);
        PlayerMock alex = server.addPlayer("Alex");
        Thread.sleep(20);
        long beforeShutdown = System.currentTimeMillis();

        server.getPluginManager().disablePlugin(plugin);

        assertFalse(plugin.isEnabled());
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite:" + plugin.getDataPath().resolve("data.db"));
                PreparedStatement select = connection.prepareStatement("SELECT last_seen FROM foliate_profiles WHERE id = ?")) {
            select.setString(1, alex.getUniqueId().toString());
            try (ResultSet row = select.executeQuery()) {
                assertTrue(row.next(), "the player was not saved");
                assertTrue(row.getLong(1) >= beforeShutdown, "the player's quit was not saved at the shutdown");
            }
        }
    }
}
