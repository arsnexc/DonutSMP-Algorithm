package com.example.donutflipscanner.provider;

import com.example.donutflipscanner.database.DatabaseManager;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.FingerprintRepository;
import com.example.donutflipscanner.database.OpportunityRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ItemFingerprintEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.market.opportunity.OpportunityState;
import com.example.donutflipscanner.diagnostics.PerformanceMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiveMarketSnapshotServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void refreshBuildsImmutableActiveAndHistorySnapshots() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("providers.db"));
        try {
            ListingRepository listings = new ListingRepository(database);
            OpportunityRepository opportunities = new OpportunityRepository(database);
            insertFingerprint(database);
            listings.upsertBatch(List.of(listing("listing-active"), listing("listing-dismissed"))).join();
            opportunities.upsert(opportunity("active", "listing-active", OpportunityState.NEW)).join();
            opportunities.upsert(opportunity("dismissed", "listing-dismissed", OpportunityState.DISMISSED)).join();
            LiveMarketSnapshotService service = service(database, listings, opportunities, NOW.plusSeconds(5));
            AtomicInteger publications = new AtomicInteger();
            Runnable unsubscribe = service.subscribe(ignored -> publications.incrementAndGet());

            LiveMarketSnapshot snapshot = service.refresh().join();

            assertTrue(snapshot.databaseAvailable());
            assertEquals(2, snapshot.activeListings());
            assertEquals(2, snapshot.databaseOpportunityCount());
            assertEquals(1, snapshot.activeOpportunityCount());
            assertEquals("active", snapshot.activeOpportunities().getFirst().opportunityId());
            assertEquals("dismissed", snapshot.history().getFirst().opportunityId());
            assertEquals(new BigDecimal("6600000"), snapshot.combinedPotentialProfit());
            assertEquals(Optional.of("{}"), snapshot.activeOpportunities().getFirst().normalizedItemMetadata());
            assertEquals(1, publications.get());
            unsubscribe.run();

            assertEquals(1, service.clearHistory().join());
            assertEquals(1, service.snapshot().databaseOpportunityCount());
            assertEquals(1, service.snapshot().activeOpportunities().size());
            assertTrue(service.snapshot().history().isEmpty());
            assertEquals(1, publications.get());
        } finally {
            database.close();
        }
    }

    @Test
    void dismissalAndManualPurchaseRefreshProviderStateAsynchronously() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("states.db"));
        try {
            ListingRepository listings = new ListingRepository(database);
            OpportunityRepository opportunities = new OpportunityRepository(database);
            insertFingerprint(database);
            listings.upsertBatch(List.of(listing("listing-one"), listing("listing-two"))).join();
            opportunities.upsert(opportunity("one", "listing-one", OpportunityState.NEW)).join();
            opportunities.upsert(opportunity("two", "listing-two", OpportunityState.NEW)).join();
            LiveMarketSnapshotService service = service(database, listings, opportunities, NOW.plusSeconds(5));
            service.refresh().join();

            assertTrue(service.updateState("one", OpportunityState.DISMISSED, "test").join());
            assertTrue(service.updateState("two", OpportunityState.PURCHASED_MANUALLY, "test").join());

            assertTrue(service.snapshot().activeOpportunities().isEmpty());
            assertEquals(2, service.snapshot().history().size());
            assertEquals(OpportunityState.DISMISSED.name(), opportunities.find("one").join().orElseThrow().state());
            assertEquals(OpportunityState.PURCHASED_MANUALLY.name(),
                    opportunities.find("two").join().orElseThrow().state());
        } finally {
            database.close();
        }
    }

    @Test
    void emptyAndUnavailableDatabaseStatesAreExplicit() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("unavailable.db"));
        ListingRepository listings = new ListingRepository(database);
        LiveMarketSnapshotService service = service(
                database, listings, new OpportunityRepository(database), NOW.plusSeconds(5)
        );
        LiveMarketSnapshot empty = service.refresh().join();
        assertTrue(empty.activeOpportunities().isEmpty());
        assertTrue(empty.databaseAvailable());

        database.close();
        LiveMarketSnapshot unavailable = service.refresh().join();

        assertFalse(unavailable.databaseAvailable());
        assertTrue(unavailable.warning().orElseThrow().contains("temporarily unavailable"));
    }

    @Test
    void staleListingMovesOpportunityOutOfLiveCardsAndIntoHistory() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("stale-provider.db"));
        try {
            ListingRepository listings = new ListingRepository(database);
            OpportunityRepository opportunities = new OpportunityRepository(database);
            insertFingerprint(database);
            listings.upsertBatch(List.of(listing("listing-stale"))).join();
            opportunities.upsert(opportunity("stale", "listing-stale", OpportunityState.NEW)).join();
            LiveMarketSnapshotService service = service(
                    database, listings, opportunities, NOW.plusSeconds(16)
            );

            LiveMarketSnapshot snapshot = service.refresh().join();

            assertTrue(snapshot.activeOpportunities().isEmpty());
            assertEquals(1, snapshot.history().size());
            assertEquals(OpportunityState.NO_LONGER_AVAILABLE.name(),
                    snapshot.history().getFirst().state());
            assertEquals(ListingState.INACTIVE_UNKNOWN,
                    listings.find("listing-stale").join().orElseThrow().state());
        } finally {
            database.close();
        }
    }

    private static LiveMarketSnapshotService service(
            DatabaseManager database,
            ListingRepository listings,
            OpportunityRepository opportunities,
            Instant now
    ) {
        return new LiveMarketSnapshotService(
                listings, new SaleRepository(database), opportunities, Optional.empty(),
                new PerformanceMetrics(), Clock.fixed(now, ZoneOffset.UTC), Duration.ofSeconds(15)
        );
    }

    private static ListingEntity listing(String key) {
        return new ListingEntity(
                key, Optional.empty(), Optional.of("seller-uuid"), Optional.of("Seller"),
                "a".repeat(64), "minecraft:netherite_ingot", 4, new BigDecimal("14200000"),
                Optional.of(new BigDecimal("3550000")), NOW.minusSeconds(30), NOW,
                Optional.of(NOW.minusSeconds(30)), Optional.empty(), ListingState.ACTIVE, 0, Optional.empty()
        );
    }

    private static OpportunityEntity opportunity(String id, String listingKey, OpportunityState state) {
        return new OpportunityEntity(
                id, listingKey, "a".repeat(64), NOW, new BigDecimal("14200000"),
                new BigDecimal("20800000"), new BigDecimal("6600000"), new BigDecimal("46"),
                new BigDecimal("87"), state.name(), Optional.empty(),
                Optional.of("Conservative completed-sale evidence"), "opportunity-v1"
        );
    }

    private static void insertFingerprint(DatabaseManager database) {
        new FingerprintRepository(database).insertIfAbsent(new ItemFingerprintEntity(
                "a".repeat(64), "minecraft:netherite_ingot", "COMMODITY", "{}", NOW
        )).join();
    }
}
