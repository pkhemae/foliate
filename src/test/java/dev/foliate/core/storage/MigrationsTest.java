package dev.foliate.core.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class MigrationsTest {

    @Test
    void statementsAreSplitOnSemicolons() {
        assertEquals(
                List.of("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)"),
                Migrations.statements("CREATE TABLE a (id INT);\nCREATE TABLE b (id INT);\n"));
    }

    /** A comment after a statement used to swallow the statement that followed it. */
    @Test
    void commentsDoNotHideTheNextStatement() {
        String script = """
                -- first table
                CREATE TABLE a (id INT); -- trailing comment
                CREATE TABLE b (
                    id INT -- the key
                );
                """;

        assertEquals(
                List.of("CREATE TABLE a (id INT)", "CREATE TABLE b (\n    id INT \n)"),
                Migrations.statements(script));
    }

    @Test
    void blankScriptHasNoStatement() {
        assertEquals(List.of(), Migrations.statements("\n-- nothing here\n;\n"));
    }
}
