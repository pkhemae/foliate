package dev.foliate.core.storage;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import de.exlll.configlib.SerializeWith;
import de.exlll.configlib.Serializer;
import dev.foliate.core.config.ConfigException;
import java.util.LinkedHashMap;
import java.util.Map;

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

    /**
     * Reads the driver options as text, whatever YAML made of them: {@code useSSL: false} is a
     * boolean and {@code connectTimeout: 5000} a number, and the driver wants both as strings.
     * A list or a section is refused, naming the option.
     */
    public static final class DriverProperties implements Serializer<Map<String, String>, Map<?, ?>> {

        public DriverProperties() {}

        @Override
        public Map<?, ?> serialize(Map<String, String> properties) {
            return new LinkedHashMap<>(properties);
        }

        @Override
        public Map<String, String> deserialize(Map<?, ?> written) {
            Map<String, String> properties = new LinkedHashMap<>();
            written.forEach((key, value) -> {
                if (value instanceof String || value instanceof Boolean || value instanceof Number) {
                    properties.put(String.valueOf(key), String.valueOf(value));
                } else {
                    throw new IllegalArgumentException("storage.mysql.properties." + key
                            + " must be a single value, such as REQUIRED, true or 5000");
                }
            });
            return properties;
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

        @Comment({
            "Options passed to the database driver as they are, for instance:",
            "  sslMode: REQUIRED",
            "  connectionTimeZone: UTC",
            "See the MySQL Connector/J documentation for the full list."
        })
        @SerializeWith(serializer = DriverProperties.class)
        private Map<String, String> properties = new LinkedHashMap<>();

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

        public Map<String, String> properties() {
            return Map.copyOf(properties);
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
