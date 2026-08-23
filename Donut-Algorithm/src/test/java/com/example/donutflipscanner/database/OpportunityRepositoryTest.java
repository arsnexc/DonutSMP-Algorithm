package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.MarketStatisticsEntity;
import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.database.entity.OpportunityStateChangeEntity;
import com.example.donutflipscanner.database.entity.ScannerMetadataEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void persistsOpportunityAndItsStateHistoryAtomically() {
        try (DatabaseManager database = preparedDatabase()) {
            OpportunityRepository opportunities = new OpportunityRepository(database);
            OpportunityEntity opportunity = DatabaseTestFixtures.opportunity("listing-1");

            opportunities.upsert(opportunity).join();
            assertTrue(opportunities.updateState(
                    opportunity.opportunityId(),
                    "DISMISSED",
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(60),
                    Optional.of("user dismissed")
            ).join());

            OpportunityEntity stored = opportunities.find(opportunity.opportunityId()).join().orElseThrow();
            List<OpportunityStateChangeEntity> history = opportunities
                    .stateHistory(opportunity.opportunityId(), 10).join();

            assertEquals("DISMISSED", stored.state());
            assertEquals(new BigDecimal("6600000"), stored.estimatedProfit());
            assertEquals(2, history.size());
            assertTrue(history.getFirst().previousState().isEmpty());
            assertEquals("NEW", history.getFirst().newState());
            assertEquals("DISMISSED", history.getLast().newState());
            assertEquals("user dismissed", history.getLast().reason().orElseThrow());
        }
    }

    @Test
    void storesStatisticsAndScannerMetadataWithoutCalculatingThem() {
        try (DatabaseManager database = preparedDatabase()) {
            MarketStatisticsRepository statistics = new MarketStatisticsRepository(database);
            ScannerMetadataRepository metadata = new ScannerMetadataRepository(database);
            Instant windowStart = DatabaseTestFixtures.BASE_TIME.minusSeconds(3600);
            MarketStatisticsEntity snapshot = new MarketStatisticsEntity(
                    "stats-1",
                    DatabaseTestFixtures.FINGERPRINT,
                    DatabaseTestFixtures.BASE_TIME,
                    windowStart,
                    DatabaseTestFixtures.BASE_TIME,
                    18,
                    Optional.of(new BigDecimal("19900000")),
                    Optional.of(new BigDecimal("22100000")),
                    Optional.of(new BigDecimal("20800000")),
                    Optional.of("{\"source\":\"test fixture\"}")
            );

            statistics.upsert(snapshot).join();
            metadata.put(new ScannerMetadataEntity(
                    "last_transaction_page",
                    "4",
                    DatabaseTestFixtures.BASE_TIME
            )).join();

            assertEquals(snapshot, statistics.findLatest(DatabaseTestFixtures.FINGERPRINT).join().orElseThrow());
            assertEquals("4", metadata.find("last_transaction_page").join().orElseThrow().value());
        }
    }

    @Test
    void expiresReviewableOpportunityWhenItsListingIsNoLongerFresh() {
        try (DatabaseManager database = preparedDatabase()) {
            OpportunityRepository opportunities = new OpportunityRepository(database);
            OpportunityEntity opportunity = DatabaseTestFixtures.opportunity("listing-1");
            opportunities.upsert(opportunity).join();

            List<String> expired = opportunities.expireUnverifiedActive(
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(1),
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(60)
            ).join();

            assertEquals(List.of(opportunity.opportunityId()), expired);
            assertEquals("NO_LONGER_AVAILABLE",
                    opportunities.find(opportunity.opportunityId()).join().orElseThrow().state());
            List<OpportunityStateChangeEntity> history = opportunities
                    .stateHistory(opportunity.opportunityId(), 10).join();
            assertEquals("NO_LONGER_AVAILABLE", history.getLast().newState());
            assertEquals(OpportunityRepository.UNVERIFIED_LISTING_REASON,
                    history.getLast().reason().orElseThrow());
        }
    }

    private DatabaseManager preparedDatabase() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("market-data.db"));
        database.ready().join();
        new FingerprintRepository(database).insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();
        new ListingRepository(database).upsertBatch(List.of(DatabaseTestFixtures.listing(
                "listing-1", new BigDecimal("14200000"), DatabaseTestFixtures.BASE_TIME
        ))).join();
        return database;
    }
}
