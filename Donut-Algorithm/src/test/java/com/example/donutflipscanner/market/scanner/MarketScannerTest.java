package com.example.donutflipscanner.market.scanner;

import com.example.donutflipscanner.api.ApiAuthenticationException;
import com.example.donutflipscanner.api.ApiException;
import com.example.donutflipscanner.api.ApiRateLimitException;
import com.example.donutflipscanner.database.DatabaseException;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketScannerTest {
    @Test
    void startsAndRunsRecentListingPoll() {
        FakeWork work = new FakeWork();
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofMillis(20), 3));
        try {
            assertTrue(scanner.start());
            await(() -> work.calls(ScannerActivity.RECENT_LISTINGS) >= 1);

            assertEquals(MarketScannerState.RUNNING, scanner.snapshot().state());
            assertEquals(4, scanner.snapshot().scheduledActivityCount());
            assertTrue(scanner.snapshot().completedRuns().get(ScannerActivity.RECENT_LISTINGS) >= 1);
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void stopsWithoutSchedulingMoreWork() {
        FakeWork work = new FakeWork();
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofMillis(15), 3));
        scanner.start();
        await(() -> work.calls(ScannerActivity.RECENT_LISTINGS) >= 1);

        scanner.stopAsync().join();
        int stoppedAt = work.calls(ScannerActivity.RECENT_LISTINGS);
        sleep(Duration.ofMillis(60));

        assertEquals(MarketScannerState.STOPPED, scanner.snapshot().state());
        assertEquals(stoppedAt, work.calls(ScannerActivity.RECENT_LISTINGS));
        scanner.shutdownAsync().join();
    }

    @Test
    void pausesAndResumes() {
        FakeWork work = new FakeWork();
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofMillis(15), 3));
        try {
            scanner.start();
            await(() -> work.calls(ScannerActivity.RECENT_LISTINGS) >= 1);
            assertTrue(scanner.pause());
            int pausedAt = work.calls(ScannerActivity.RECENT_LISTINGS);
            sleep(Duration.ofMillis(50));
            assertEquals(pausedAt, work.calls(ScannerActivity.RECENT_LISTINGS));
            assertEquals(MarketScannerState.PAUSED, scanner.snapshot().state());

            assertTrue(scanner.resume());
            await(() -> work.calls(ScannerActivity.RECENT_LISTINGS) > pausedAt);
            assertEquals(MarketScannerState.RUNNING, scanner.snapshot().state());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void refusesToStartWithoutApiKey() {
        FakeWork work = new FakeWork();
        MarketScanner scanner = new MarketScanner(work, config(false, Duration.ofMillis(20), 3));
        try {
            assertFalse(scanner.start());
            assertEquals(MarketScannerState.ERROR, scanner.snapshot().state());
            assertEquals(ScannerPauseReason.API_KEY_MISSING, scanner.snapshot().pauseReason());
            assertEquals(0, work.calls(ScannerActivity.RECENT_LISTINGS));
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void startsAutomaticallyWhenApiKeyCapabilityBecomesValid() {
        FakeWork work = new FakeWork();
        MarketScanner scanner = new MarketScanner(work, config(false, Duration.ofMillis(20), 3));
        try {
            assertFalse(scanner.start());

            scanner.updateConfiguration(config(true, Duration.ofMillis(20), 3));
            await(() -> scanner.snapshot().state() == MarketScannerState.RUNNING
                    && work.calls(ScannerActivity.RECENT_LISTINGS) >= 1);
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void authenticationFailureStopsSchedulingAndUsesSanitizedError() {
        FakeWork work = new FakeWork();
        work.set(ScannerActivity.RECENT_LISTINGS, () ->
                CompletableFuture.failedFuture(new ApiAuthenticationException(401))
        );
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofMillis(20), 3));
        try {
            scanner.start();
            await(() -> scanner.snapshot().state() == MarketScannerState.ERROR);

            assertEquals(ScannerPauseReason.API_KEY_MISSING, scanner.snapshot().pauseReason());
            assertEquals("API authentication failed.", scanner.snapshot().lastSanitizedError().orElseThrow());
            assertEquals(0, scanner.snapshot().scheduledActivityCount());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void rateLimitPausesThenResumesAfterCooldown() {
        FakeWork work = new FakeWork();
        AtomicInteger attempts = new AtomicInteger();
        work.set(ScannerActivity.RECENT_LISTINGS, () -> attempts.incrementAndGet() == 1
                ? CompletableFuture.failedFuture(new ApiRateLimitException(Duration.ofMillis(100)))
                : CompletableFuture.completedFuture(emptyResult()));
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofSeconds(1), 3));
        try {
            scanner.start();
            await(() -> scanner.snapshot().state() == MarketScannerState.RATE_LIMITED);
            assertEquals(ScannerPauseReason.API_RATE_LIMIT, scanner.snapshot().pauseReason());

            await(() -> scanner.snapshot().state() == MarketScannerState.RUNNING && attempts.get() >= 2);
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void temporaryApiFailureDoesNotImmediatelyKillScanner() {
        FakeWork work = new FakeWork();
        work.set(ScannerActivity.RECENT_LISTINGS, () -> CompletableFuture.failedFuture(
                new ApiException("sanitized temporary failure", 503, true)
        ));
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofSeconds(1), 3));
        try {
            scanner.start();
            await(() -> scanner.snapshot().consecutiveTemporaryFailures() == 1
                    && scanner.snapshot().lastSanitizedError().isPresent());

            assertEquals(MarketScannerState.RUNNING, scanner.snapshot().state());
            assertEquals("Market API is temporarily unavailable.",
                    scanner.snapshot().lastSanitizedError().orElseThrow());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void databaseFailureMovesScannerToError() {
        FakeWork work = new FakeWork();
        work.set(ScannerActivity.RECENT_LISTINGS, () -> CompletableFuture.failedFuture(
                new DatabaseException("sensitive path must not escape", new IllegalStateException())
        ));
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofSeconds(1), 3));
        try {
            scanner.start();
            await(() -> scanner.snapshot().state() == MarketScannerState.ERROR);

            assertEquals(ScannerPauseReason.DATABASE_FAILURE, scanner.snapshot().pauseReason());
            assertEquals("Market database operation failed.",
                    scanner.snapshot().lastSanitizedError().orElseThrow());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void cleanShutdownWaitsForInflightWorkAndFlushesResources() {
        FakeWork work = new FakeWork();
        CompletableFuture<ScanBatchResult> pending = new CompletableFuture<>();
        work.set(ScannerActivity.RECENT_LISTINGS, () -> pending);
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofSeconds(1), 3));
        scanner.start();
        await(() -> work.calls(ScannerActivity.RECENT_LISTINGS) == 1);

        CompletableFuture<Void> shutdown = scanner.shutdownAsync();
        assertFalse(shutdown.isDone());
        pending.complete(emptyResult());
        shutdown.join();

        assertEquals(MarketScannerState.STOPPED, scanner.snapshot().state());
        assertTrue(work.flushed.get());
        assertTrue(work.saved.get());
        assertTrue(work.closed.get());
    }

    @Test
    void startIsIdempotentAndDoesNotDuplicateSchedules() {
        FakeWork work = new FakeWork();
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofMillis(30), 3));
        try {
            assertTrue(scanner.start());
            assertFalse(scanner.start());
            assertEquals(4, scanner.snapshot().scheduledActivityCount());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void intervalChangeReplacesSchedulesAndTakesEffect() {
        FakeWork work = new FakeWork();
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofSeconds(2), 3));
        try {
            scanner.start();
            await(() -> work.calls(ScannerActivity.RECENT_LISTINGS) >= 1);
            int before = work.calls(ScannerActivity.RECENT_LISTINGS);

            scanner.updateConfiguration(config(true, Duration.ofMillis(20), 3));
            await(() -> work.calls(ScannerActivity.RECENT_LISTINGS) > before);

            assertEquals(4, scanner.snapshot().scheduledActivityCount());
            assertEquals(MarketScannerState.RUNNING, scanner.snapshot().state());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void disabledScannerDoesNotScheduleWork() {
        FakeWork work = new FakeWork();
        MarketScannerConfig disabled = new MarketScannerConfig(
                false, true, true, false, true,
                Duration.ofMillis(20), Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1),
                Duration.ofSeconds(1), 3
        );
        MarketScanner scanner = new MarketScanner(work, disabled);
        try {
            assertFalse(scanner.start());
            assertEquals(MarketScannerState.STOPPED, scanner.snapshot().state());
            assertEquals(ScannerPauseReason.SCANNER_DISABLED, scanner.snapshot().pauseReason());
            assertEquals(0, scanner.snapshot().scheduledActivityCount());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void disablingRunningScannerStopsFuturePolls() {
        FakeWork work = new FakeWork();
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofMillis(15), 3));
        try {
            scanner.start();
            await(() -> work.calls(ScannerActivity.RECENT_LISTINGS) >= 1);
            MarketScannerConfig disabled = new MarketScannerConfig(
                    false, true, true, false, true,
                    Duration.ofMillis(15), Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1),
                    Duration.ofSeconds(1), 3
            );

            scanner.updateConfiguration(disabled);
            await(() -> scanner.snapshot().state() == MarketScannerState.STOPPED);
            int stoppedAt = work.calls(ScannerActivity.RECENT_LISTINGS);
            sleep(Duration.ofMillis(50));

            assertEquals(stoppedAt, work.calls(ScannerActivity.RECENT_LISTINGS));
            assertEquals(ScannerPauseReason.SCANNER_DISABLED, scanner.snapshot().pauseReason());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    @Test
    void recalculatesStatisticsOnlyWhenMarketDataChanges() {
        FakeWork work = new FakeWork();
        AtomicInteger polls = new AtomicInteger();
        work.set(ScannerActivity.RECENT_LISTINGS, () -> {
            if (polls.incrementAndGet() == 1) {
                return CompletableFuture.completedFuture(new ScanBatchResult(
                        1, 1, 0, 0, Set.of("fingerprint"), Set.of("listing"),
                        Optional.of("hash"), Optional.of("listing"), Instant.now()
                ));
            }
            return CompletableFuture.completedFuture(emptyResult());
        });
        MarketScanner scanner = new MarketScanner(work, config(true, Duration.ofMillis(20), 3));
        try {
            scanner.start();
            await(() -> work.calls(ScannerActivity.STATISTICS_RECALCULATION) == 1);
            await(() -> polls.get() >= 2);
            sleep(Duration.ofMillis(40));

            assertEquals(1, work.calls(ScannerActivity.STATISTICS_RECALCULATION));
            assertEquals(1, scanner.snapshot().dataVersions().listings());
            assertEquals(1, scanner.snapshot().dataVersions().statistics());
        } finally {
            scanner.shutdownAsync().join();
        }
    }

    private static MarketScannerConfig config(boolean apiKey, Duration recentInterval, int maxFailures) {
        return new MarketScannerConfig(
                true, apiKey, true, false, true,
                recentInterval, Duration.ofDays(1), Duration.ofDays(1), Duration.ofDays(1),
                Duration.ofSeconds(1), maxFailures
        );
    }

    private static ScanBatchResult emptyResult() {
        return ScanBatchResult.empty(Instant.now());
    }

    private static void await(BooleanSupplier condition) {
        Instant deadline = Instant.now().plusSeconds(2);
        while (!condition.getAsBoolean()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("condition was not met before timeout");
            }
            sleep(Duration.ofMillis(5));
        }
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test interrupted", exception);
        }
    }

    private static final class FakeWork implements MarketScanWork {
        private final EnumMap<ScannerActivity, AtomicInteger> calls = new EnumMap<>(ScannerActivity.class);
        private final EnumMap<ScannerActivity, Supplier<CompletableFuture<ScanBatchResult>>> work =
                new EnumMap<>(ScannerActivity.class);
        private final AtomicBoolean flushed = new AtomicBoolean();
        private final AtomicBoolean saved = new AtomicBoolean();
        private final AtomicBoolean closed = new AtomicBoolean();

        private FakeWork() {
            for (ScannerActivity activity : ScannerActivity.values()) {
                calls.put(activity, new AtomicInteger());
                work.put(activity, () -> CompletableFuture.completedFuture(emptyResult()));
            }
        }

        private void set(ScannerActivity activity, Supplier<CompletableFuture<ScanBatchResult>> action) {
            work.put(activity, action);
        }

        private int calls(ScannerActivity activity) {
            return calls.get(activity).get();
        }

        private CompletableFuture<ScanBatchResult> invoke(ScannerActivity activity) {
            calls.get(activity).incrementAndGet();
            return work.get(activity).get();
        }

        @Override
        public CompletableFuture<ScanBatchResult> pollRecentlyListed() {
            return invoke(ScannerActivity.RECENT_LISTINGS);
        }

        @Override
        public CompletableFuture<ScanBatchResult> pollCompletedTransactions() {
            return invoke(ScannerActivity.COMPLETED_TRANSACTIONS);
        }

        @Override
        public CompletableFuture<ScanBatchResult> refreshActiveListings() {
            return invoke(ScannerActivity.ACTIVE_LISTING_REFRESH);
        }

        @Override
        public CompletableFuture<ScanBatchResult> recalculateStatistics(Set<String> changedFingerprints) {
            ScanBatchResult result = new ScanBatchResult(
                    changedFingerprints.size(), changedFingerprints.size(), 0, 0,
                    changedFingerprints, Set.of(), Optional.empty(), Optional.empty(), Instant.now()
            );
            calls.get(ScannerActivity.STATISTICS_RECALCULATION).incrementAndGet();
            return CompletableFuture.completedFuture(result);
        }

        @Override
        public CompletableFuture<ScanBatchResult> runRetentionCleanup() {
            return invoke(ScannerActivity.RETENTION_CLEANUP);
        }

        @Override
        public CompletableFuture<Void> flushPendingWrites() {
            flushed.set(true);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> saveConfiguration() {
            saved.set(true);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed.set(true);
        }
    }
}
