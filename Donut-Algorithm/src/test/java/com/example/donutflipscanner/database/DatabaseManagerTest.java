package com.example.donutflipscanner.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsDatabaseAndRunsAllMigrations() {
        Path databasePath = temporaryDirectory.resolve("nested").resolve("market-data.db");

        try (DatabaseManager database = new DatabaseManager(databasePath)) {
            database.ready().join();

            assertTrue(Files.isRegularFile(databasePath));
            assertEquals(5, database.schemaVersion().join());
            assertEquals("ok", database.integrityCheck().join());
        }
    }

    @Test
    void defaultPathMatchesTheConfiguredModDirectory() {
        assertEquals(
                Path.of("config", "donut-flip-scanner", "market-data.db"),
                DatabaseManager.DEFAULT_RELATIVE_PATH
        );
    }
}
