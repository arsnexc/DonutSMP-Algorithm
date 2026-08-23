package com.example.donutflipscanner.diagnostics;

import com.example.donutflipscanner.api.ApiConnectionState;
import com.example.donutflipscanner.market.scanner.MarketScannerState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiagnosticsExporterTest {
    @Test
    void containsRequiredMetricsWithoutSecretsOrPersonalPaths() {
        PerformanceMetrics metrics = new PerformanceMetrics();
        metrics.record(PerformanceOperation.LISTING_EVALUATION, 2_000_000L);
        DiagnosticsSnapshot snapshot = new DiagnosticsSnapshot(
                Instant.parse("2026-08-03T12:00:00Z"),
                new DiagnosticsVersions("0.1.0", "1.21.11", "0.19.3"),
                MarketScannerState.RUNNING, ApiConnectionState.CONNECTED,
                3, 2, 1, 3, Duration.ofMillis(12),
                new DatabaseRecordCounts(1, 2, 3, 4, 5), 3,
                metrics.snapshot(new CacheStatistics(1, 2, 3, 4_096), 0, 2),
                Optional.of("Authorization: Bearer secret C:\\Users\\Alice\\debug.txt")
        );

        String json = new DiagnosticsExporter().encode(snapshot);

        assertTrue(json.contains("LISTING_EVALUATION"));
        assertTrue(json.contains("latestMigrationVersion"));
        assertFalse(json.contains("Bearer secret"));
        assertFalse(json.contains("Alice"));
        assertFalse(json.toLowerCase(java.util.Locale.ROOT).contains("api-key"));
    }
}
