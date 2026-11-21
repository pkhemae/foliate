package dev.foliate.modules.profile;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * The MySQL or MariaDB server that the environment gives to the storage tests. They run only when
 * {@code FOLIATE_TEST_MYSQL_PORT} is set, as CI does; the other variables default to a local server
 * whose root account has no password, and to a database named {@code foliate_test}. The tests drop
 * tables of that database: point it at a throwaway one, never at the data of a real server.
 */
final class TestMysql {

    static final String HOST = env("FOLIATE_TEST_MYSQL_HOST", "127.0.0.1");
    static final String PORT = env("FOLIATE_TEST_MYSQL_PORT", "3306");
    static final String DATABASE = env("FOLIATE_TEST_MYSQL_DATABASE", "foliate_test");
    static final String USER = env("FOLIATE_TEST_MYSQL_USER", "root");
    static final String PASSWORD = env("FOLIATE_TEST_MYSQL_PASSWORD", "");

    private TestMysql() {}

    static String url() {
        return "jdbc:mysql://" + HOST + ":" + PORT + "/" + DATABASE;
    }

    static Connection connect() throws SQLException {
        return DriverManager.getConnection(url(), USER, PASSWORD);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
