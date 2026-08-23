package com.example.donutflipscanner.database;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.model.TradeExecutionState;
import com.example.donutflipscanner.database.entity.TradeExecutionEntity;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeExecutionRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void journalsTransitionsAndOutcome() {
        try (DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("journal.db"))) {
            TradeExecutionRepository repository = new TradeExecutionRepository(database);
            TradeExecutionRequest request = request("execution-1");

            repository.recordTransition(request, TradeExecutionState.QUEUED,
                    TradeExecutionState.PRECHECK, "precheck", DatabaseTestFixtures.BASE_TIME).join();
            repository.recordTransition(request, TradeExecutionState.PRECHECK,
                    TradeExecutionState.COMPLETED, "done", DatabaseTestFixtures.BASE_TIME.plusSeconds(1)).join();
            repository.recordOutcome(request.executionId(), Optional.of(new BigDecimal("66500")),
                    true, true, DatabaseTestFixtures.BASE_TIME.plusSeconds(1)).join();

            TradeExecutionEntity stored = repository.find(request.executionId()).join().orElseThrow();
            assertEquals(TradeExecutionState.COMPLETED, stored.state());
            assertEquals(new BigDecimal("66500"), stored.relistPrice().orElseThrow());
            assertTrue(stored.purchaseConfirmed());
            assertTrue(stored.listingConfirmed());
            assertEquals(2, repository.transitions(request.executionId(), 10).join().size());
        }
    }

    @Test
    void startupRecoveryMarksUnfinishedExecutionsForManualReview() {
        try (DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("recovery.db"))) {
            TradeExecutionRepository repository = new TradeExecutionRepository(database);
            TradeExecutionRequest request = request("execution-2");
            repository.recordTransition(request, TradeExecutionState.QUEUED,
                    TradeExecutionState.PURCHASING, "purchase began", DatabaseTestFixtures.BASE_TIME).join();

            assertEquals(1, repository.markInterruptedExecutionsForReview(
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(30)).join());
            TradeExecutionEntity stored = repository.find(request.executionId()).join().orElseThrow();
            assertEquals(TradeExecutionState.INTERRUPTED_REQUIRES_REVIEW, stored.state());
            assertFalse(stored.purchaseConfirmed());
            assertEquals(2, repository.transitions(request.executionId(), 10).join().size());
            assertEquals(0, repository.markInterruptedExecutionsForReview(
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(60)).join());
        }
    }

    private static TradeExecutionRequest request(String id) {
        return new TradeExecutionRequest(
                id, "opportunity", "listing", "fingerprint", "minecraft:diamond_leggings",
                1, Optional.of("Seller"), new BigDecimal("25000"), new BigDecimal("66500"),
                new BigDecimal("37000"), new BigDecimal("149"), 60, 8, MarketRiskLevel.LOW,
                DatabaseTestFixtures.BASE_TIME.minusSeconds(2), new BigDecimal("25000"), 30,
                AutomationMode.DRY_RUN
        );
    }
}
