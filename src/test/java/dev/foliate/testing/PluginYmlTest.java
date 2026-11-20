package dev.foliate.testing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.commands.Permissions;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.ServerMock;

class PluginYmlTest {

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
    void declaredPermissionsAreRegisteredWithTheirDefaults() {
        PluginYml.registerPermissions(server);

        assertTrue(PluginYml.permissions().contains(Permissions.COMMAND_INFO));
        assertEquals(PermissionDefault.OP, server.getPluginManager().getPermission(Permissions.COMMAND_INFO).getDefault());
        assertEquals(PermissionDefault.TRUE, server.getPluginManager().getPermission(Permissions.COMMAND_MENU).getDefault());
    }
}
