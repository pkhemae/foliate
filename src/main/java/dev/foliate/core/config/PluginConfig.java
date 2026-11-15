package dev.foliate.core.config;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import java.util.List;

/**
 * The content of {@code config.yml}. Adding a setting means adding a field here: the file is
 * regenerated from this class, comments included, and keys missing from an older file take the
 * field's initial value.
 *
 * <p>Instances are never modified after loading. Fields cannot be {@code final} because the
 * library sets them by reflection, so there are no setters: a reload publishes a new instance.
 * Getters of collection fields must return a copy ({@code List.copyOf}): the library fills them
 * with mutable collections, which must not be shared between threads.
 */
@Configuration
public final class PluginConfig {

    @Comment({"Internal version of this file, used to migrate it when the plugin updates.", "Do not edit."})
    @SuppressWarnings("unused") // Read and written by the library; the migrator works on the raw file.
    private int configVersion = ConfigMigrator.CURRENT_VERSION;

    @Comment("Logs extra details that help when reporting a problem.")
    private boolean debug = false;

    @Comment({
        "Language of the console, and of players whose game language has no file in the lang folder.",
        "Must be the name of a file in that folder, without the extension."
    })
    private String defaultLocale = "en";

    @Comment("Scoreboard shown on the right of each player's screen.")
    private Sidebar sidebar = new Sidebar();

    private PluginConfig() {}

    /** The configuration a fresh install starts with. */
    static PluginConfig defaults() {
        return new PluginConfig();
    }

    public boolean debug() {
        return debug;
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    public Sidebar sidebar() {
        return sidebar;
    }

    /**
     * Checks what the types alone cannot express. Runs before a loaded configuration is published
     * or written back, and its message is shown to the administrator: name the setting as it
     * appears in the file.
     *
     * @throws ConfigException with a message an administrator can act on
     */
    void validate() throws ConfigException {
        if (defaultLocale.isBlank()) {
            throw new ConfigException("default-locale must not be empty");
        }
        sidebar.validate();
    }

    /** The {@code sidebar} section. */
    @Configuration
    public static final class Sidebar {

        /** The client cannot display more lines than this. */
        private static final int MAX_LINES = 15;

        private static final char LEGACY_CODE = '\u00A7';

        private boolean enabled = true;

        @Comment("Ticks between two refreshes (20 ticks = 1 second).")
        private int refreshTicks = 20;

        @Comment({
            "Title and lines use the MiniMessage format.",
            "Available tags: <player>, <online>, and <papi:placeholder> when PlaceholderAPI is installed."
        })
        private String title = "<aqua><bold>Foliate";

        private List<String> lines = List.of("", "<gray>Player: <white><player>", "<gray>Online: <white><online>", "");

        private Sidebar() {}

        public boolean enabled() {
            return enabled;
        }

        public int refreshTicks() {
            return refreshTicks;
        }

        public String title() {
            return title;
        }

        public List<String> lines() {
            return List.copyOf(lines);
        }

        private void validate() throws ConfigException {
            if (refreshTicks < 1) {
                throw new ConfigException("sidebar.refresh-ticks must be at least 1");
            }
            if (lines.size() > MAX_LINES) {
                throw new ConfigException("sidebar.lines cannot have more than " + MAX_LINES + " lines");
            }
            // MiniMessage refuses legacy codes outright: caught here, it is one clear message at
            // load time instead of an error on every refresh for every player.
            if (title.indexOf(LEGACY_CODE) >= 0 || lines.stream().anyMatch(line -> line.indexOf(LEGACY_CODE) >= 0)) {
                throw new ConfigException(
                        "sidebar.title and sidebar.lines use the MiniMessage format: write <red> instead of legacy colour codes");
            }
        }
    }
}
