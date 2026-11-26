package dev.foliate;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import dev.foliate.core.config.ConfigException;
import dev.foliate.core.config.ConfigRoot;
import dev.foliate.core.config.ConfigService;
import dev.foliate.core.storage.StorageSettings;
import dev.foliate.modules.scoreboard.config.ScoreboardSettings;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.slf4j.Logger;

/**
 * The content of {@code config.yml}: the settings of every part of the plugin, side by side. Each
 * module and each service owns its own section (a class next to it), and this class only composes
 * them into the single file administrators edit. To add a setting, add a field to the class of the
 * part that uses it; to add a section, add a field here.
 *
 * <p>The file is generated from these classes, comments included, and keys missing from an older
 * file take the field's initial value.
 *
 * <p>Instances are never modified after loading. Fields cannot be {@code final} because the
 * library sets them by reflection, so there are no setters: a reload publishes a new instance.
 * Getters of collection fields must return a copy ({@code List.copyOf}): the library fills them
 * with mutable collections, which must not be shared between threads.
 */
@Configuration
public final class PluginConfig implements ConfigRoot {

    /**
     * Steps that upgrade a {@code config.yml} written by an older version, needed when a setting is
     * renamed or moved. The step at index {@code i} upgrades a file from version {@code i + 1}.
     * Example: {@code yaml -> ConfigMigrator.rename(yaml, "old-name", "section.new-name")}.
     */
    static final List<Consumer<Map<String, Object>>> MIGRATIONS = List.of();

    /** The version of the current format of the file. */
    static final int VERSION = MIGRATIONS.size() + 1;

    private static final String HEADER = """
            Foliate configuration.

            The plugin rewrites this file when it adds new settings: your values are kept,
            but comments you add and settings it does not know are removed.
            Write true or false for on/off settings.""";

    @Comment({"Internal version of this file, used to migrate it when the plugin updates.", "Do not edit."})
    private int configVersion = VERSION;

    @Comment("Scoreboard shown on the right of each player's screen.")
    private ScoreboardSettings scoreboard = new ScoreboardSettings();

    @Comment({"Where player data is kept.", "Changes in this section only apply after a server restart."})
    private StorageSettings storage = new StorageSettings();

    private PluginConfig() {}

    /** The service that loads {@code config.yml} into this class. */
    public static ConfigService<PluginConfig> service(Path dataDirectory, Logger logger) {
        return new ConfigService<>(dataDirectory, "config.yml", logger, PluginConfig.class, PluginConfig::new, MIGRATIONS, HEADER);
    }

    @Override
    public int configVersion() {
        return configVersion;
    }

    public ScoreboardSettings scoreboard() {
        return scoreboard;
    }

    public StorageSettings storage() {
        return storage;
    }

    @Override
    public void validate() throws ConfigException {
        scoreboard.validate();
        storage.validate();
    }
}
