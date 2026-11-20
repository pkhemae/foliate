package dev.foliate.modules.profile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.foliate.PluginConfig;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.storage.Database;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.helpers.NOPLogger;

/**
 * What the repository writes is what it reads back, field for field. When you add a value to
 * {@link PlayerProfile}, give it a different number in each profile below: a column forgotten in
 * the {@code INSERT}, the {@code UPDATE} or the {@code SELECT} fails here, and nowhere else.
 */
class ProfileRepositoryTest {

    @TempDir
    Path directory;

    private Database database;
    private final ProfileRepository repository = new ProfileRepository();

    @BeforeEach
    void setUp() throws Exception {
        ConfigService<PluginConfig> config = PluginConfig.service(directory, NOPLogger.NOP_LOGGER);
        config.enable();
        database = new Database(directory, () -> config.get().storage(), ProfileModule.MIGRATIONS, NOPLogger.NOP_LOGGER);
        database.enable();
    }

    @AfterEach
    void tearDown() {
        database.disable();
    }

    @Test
    void aNewProfileIsReadBackWithEveryField() throws Exception {
        PlayerProfile profile = new PlayerProfile(UUID.randomUUID(), "Alex", 1_000, 2_000, 3);

        database.call(connection -> {
            repository.save(connection, profile);
            return null;
        });

        assertEquals(profile, find(profile.id()));
    }

    @Test
    void aProfileSavedAgainTakesEveryChangedField() throws Exception {
        UUID id = UUID.randomUUID();
        PlayerProfile first = new PlayerProfile(id, "Alex", 1_000, 2_000, 3);
        // The first sighting does not change: everything else does.
        PlayerProfile changed = new PlayerProfile(id, "Alex2", 1_000, 5_000, 9);

        database.call(connection -> {
            repository.save(connection, first);
            repository.save(connection, changed);
            return null;
        });

        assertEquals(changed, find(id));
    }

    @Test
    void aProfileThatWasNeverSavedIsNotFound() throws Exception {
        assertTrue(database.call(connection -> repository.find(connection, UUID.randomUUID())).isEmpty());
    }

    private PlayerProfile find(UUID id) throws Exception {
        return database.call(connection -> repository.find(connection, id)).orElseThrow();
    }
}
