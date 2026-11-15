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

    @Comment({
        "Sends anonymous usage statistics (server version, player count, plugin version) to bStats.org.",
        "This is an outbound connection. It can also be turned off for every plugin in plugins/bStats/config.yml."
    })
    private boolean metrics = true;

    @Comment({
        "Asks GitHub once at startup whether a newer release exists, and says so in the console.",
        "This is an outbound connection. Nothing about the server is sent."
    })
    private boolean updateCheck = true;

    @Comment("Scoreboard shown on the right of each player's screen.")
    private Sidebar sidebar = new Sidebar();

    @Comment({"Where player data is kept.", "Changes in this section only apply after a server restart."})
    private Storage storage = new Storage();

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

    public boolean metrics() {
        return metrics;
    }

    public boolean updateCheck() {
        return updateCheck;
    }

    public Sidebar sidebar() {
        return sidebar;
    }

    public Storage storage() {
        return storage;
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
        storage.validate();
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
            "Available tags: <player>, <online>, and <papi:placeholder> when PlaceholderAPI is installed,",
            "for example <papi:foliate_joins>."
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

    /** The {@code storage} section. */
    @Configuration
    public static final class Storage {

        public enum Type {
            SQLITE,
            MYSQL
        }

        @Comment("Set to false to run without a database. Features that need one are then turned off.")
        private boolean enabled = true;

        @Comment({
            "SQLITE keeps everything in a file in this folder and needs no setup.",
            "MYSQL connects to a MySQL or MariaDB server, using the settings below."
        })
        private Type type = Type.SQLITE;

        private Mysql mysql = new Mysql();

        private Storage() {}

        public boolean enabled() {
            return enabled;
        }

        public Type type() {
            return type;
        }

        public Mysql mysql() {
            return mysql;
        }

        private void validate() throws ConfigException {
            mysql.validate();
        }
    }

    /** The {@code storage.mysql} section. */
    @Configuration
    public static final class Mysql {

        private String host = "localhost";
        private int port = 3306;
        private String database = "foliate";
        private String username = "foliate";
        private String password = "";

        @Comment("Maximum number of connections kept open.")
        private int poolSize = 4;

        private Mysql() {}

        public String host() {
            return host;
        }

        public int port() {
            return port;
        }

        public String database() {
            return database;
        }

        public String username() {
            return username;
        }

        public String password() {
            return password;
        }

        public int poolSize() {
            return poolSize;
        }

        private void validate() throws ConfigException {
            if (port < 1 || port > 65535) {
                throw new ConfigException("storage.mysql.port must be between 1 and 65535");
            }
            if (poolSize < 1) {
                throw new ConfigException("storage.mysql.pool-size must be at least 1");
            }
        }
    }
}
