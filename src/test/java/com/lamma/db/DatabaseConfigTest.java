package com.lamma.db;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DatabaseConfigTest {

    @TempDir
    Path dir;

    @Test
    void readsSettingsFromPropertiesFile() throws Exception {
        Path file = dir.resolve("database.properties");
        Files.writeString(file, "db.url=jdbc:mysql://localhost:3306/lamma\ndb.user=alice\ndb.password=secret\n");

        DatabaseConfig config = DatabaseConfig.load(file, Map.of());

        assertEquals("jdbc:mysql://localhost:3306/lamma", config.url());
        assertEquals("alice", config.user());
        assertEquals("secret", config.password());
    }

    @Test
    void environmentVariablesOverrideFile() throws Exception {
        Path file = dir.resolve("database.properties");
        Files.writeString(file, "db.url=jdbc:mysql://localhost:3306/lamma\ndb.user=alice\ndb.password=secret\n");

        DatabaseConfig config = DatabaseConfig.load(file, Map.of("LAMMA_DB_PASSWORD", "from-env"));

        assertEquals("alice", config.user());
        assertEquals("from-env", config.password());
    }

    @Test
    void environmentAloneIsEnoughWhenFileIsMissing() throws Exception {
        DatabaseConfig config = DatabaseConfig.load(dir.resolve("missing.properties"), Map.of(
                "LAMMA_DB_URL", "jdbc:mysql://localhost:3306/lamma",
                "LAMMA_DB_USER", "bob",
                "LAMMA_DB_PASSWORD", "pw"));

        assertEquals("bob", config.user());
    }

    @Test
    void missingSettingsGiveAHelpfulMessage() {
        DatabaseException e = assertThrows(DatabaseException.class,
                () -> DatabaseConfig.load(dir.resolve("missing.properties"), Map.of()));

        assertTrue(e.getMessage().contains("database.properties.example"), e.getMessage());
    }

    @Test
    void toStringDoesNotRevealPassword() {
        DatabaseConfig config = new DatabaseConfig("jdbc:mysql://localhost/lamma", "alice", "top-secret");

        assertFalse(config.toString().contains("top-secret"));
    }
}
