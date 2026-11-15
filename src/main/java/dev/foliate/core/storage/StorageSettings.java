package dev.foliate.core.storage;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import dev.foliate.core.config.ConfigException;

/** The {@code storage} section of {@code config.yml}: where the {@link Database} keeps its data. */
@Configuration
public final class StorageSettings {

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

    public StorageSettings() {}

    public boolean enabled() {
        return enabled;
    }

    public Type type() {
        return type;
    }

    public Mysql mysql() {
        return mysql;
    }

    /**
     * Checks what the types alone cannot express.
     *
     * @throws ConfigException with a message an administrator can act on
     */
    public void validate() throws ConfigException {
        mysql.validate();
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

        public Mysql() {}

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

        void validate() throws ConfigException {
            if (port < 1 || port > 65535) {
                throw new ConfigException("storage.mysql.port must be between 1 and 65535");
            }
            if (poolSize < 1) {
                throw new ConfigException("storage.mysql.pool-size must be at least 1");
            }
        }
    }
}
