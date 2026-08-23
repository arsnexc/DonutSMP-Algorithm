package com.example.donutflipscanner;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProductionRuntimeTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void missingCredentialKeepsMockFallbackWithoutCreatingProductionFiles() {
        var runtime = ClientProductionRuntime.startAsync(temporaryDirectory).join();

        assertTrue(runtime.isEmpty());
        assertFalse(Files.exists(temporaryDirectory.resolve("market-data.db")));
        assertFalse(Files.exists(temporaryDirectory.resolve("config.json")));
    }
}
