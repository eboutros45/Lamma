package com.lamma.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class SchemaMigratorTest {

    @Test
    void splitsStatementsAndSkipsComments() {
        String script = "-- comment\n"
                + "CREATE TABLE a (\n"
                + "  id INT -- inline is kept\n"
                + ");\n"
                + "\n"
                + "-- another comment\n"
                + "CREATE TABLE b (id INT);\n";

        List<String> statements = SchemaMigrator.splitStatements(script);

        assertEquals(2, statements.size());
        assertEquals("CREATE TABLE a (\n  id INT -- inline is kept\n)", statements.get(0));
        assertEquals("CREATE TABLE b (id INT)", statements.get(1));
    }

    @Test
    void everyListedMigrationExistsOnTheClasspath() {
        for (String script : SchemaMigrator.MIGRATIONS) {
            assertNotNull(SchemaMigrator.class.getResource("/db/migration/" + script), script);
        }
    }

    @Test
    void migrationFileNamesMatchTheirVersion() {
        for (int i = 0; i < SchemaMigrator.MIGRATIONS.size(); i++) {
            String expectedPrefix = String.format("V%03d__", i + 1);
            assertEquals(expectedPrefix, SchemaMigrator.MIGRATIONS.get(i).substring(0, expectedPrefix.length()));
        }
    }
}
