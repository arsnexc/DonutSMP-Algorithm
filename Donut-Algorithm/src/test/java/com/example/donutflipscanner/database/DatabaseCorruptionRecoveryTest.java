package com.example.donutflipscanner.database;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseCorruptionRecoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesCorruptHistoryAndRefusesAutomaticReplacement() throws Exception {
        Path path = temporaryDirectory.resolve("market-data.db");
        byte[] original = "not-a-sqlite-database".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(path, original);
        try (DatabaseManager database = new DatabaseManager(path)) {
            CompletionException failure = assertThrows(CompletionException.class, () -> database.ready().join());
            DatabaseRecoveryException recovery = assertInstanceOf(
                    DatabaseRecoveryException.class, failure.getCause()
            );
            assertFalse(recovery.backupFiles().isEmpty());
            assertTrue(Files.isRegularFile(recovery.backupFiles().getFirst()));
            assertArrayEquals(original, Files.readAllBytes(recovery.backupFiles().getFirst()));
            assertArrayEquals(original, Files.readAllBytes(path));
        }
    }
}
