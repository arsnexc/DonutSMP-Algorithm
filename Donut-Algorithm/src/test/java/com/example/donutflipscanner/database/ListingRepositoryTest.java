package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListingRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void duplicateListingUpdatesLastSeenWithoutCreatingAnotherRow() {
        try (DatabaseManager database = database()) {
            FingerprintRepository fingerprints = new FingerprintRepository(database);
            ListingRepository listings = new ListingRepository(database);
            fingerprints.insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();

            ListingEntity first = DatabaseTestFixtures.listing(
                    "listing-1", new BigDecimal("14200000"), DatabaseTestFixtures.BASE_TIME
            );
            ListingEntity updated = DatabaseTestFixtures.listing(
                    "listing-1", new BigDecimal("13900000"), DatabaseTestFixtures.BASE_TIME.plusSeconds(10)
            );

            assertEquals(1, listings.upsertBatch(List.of(first)).join().inserted());
            assertEquals(0, listings.upsertBatch(List.of(updated)).join().inserted());
            assertEquals(1L, listings.count().join());

            ListingEntity stored = listings.find("listing-1").join().orElseThrow();
            assertEquals(new BigDecimal("13900000"), stored.listingPrice());
            assertEquals(DatabaseTestFixtures.BASE_TIME, stored.firstSeenAt());
            assertEquals(DatabaseTestFixtures.BASE_TIME.plusSeconds(10), stored.lastSeenAt());
        }
    }

    @Test
    void repeatedMissingEvidenceTransitionsConservatively() {
        try (DatabaseManager database = database()) {
            FingerprintRepository fingerprints = new FingerprintRepository(database);
            ListingRepository listings = new ListingRepository(database);
            fingerprints.insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();
            listings.upsertBatch(List.of(DatabaseTestFixtures.listing(
                    "listing-1", BigDecimal.TEN, DatabaseTestFixtures.BASE_TIME
            ))).join();

            assertEquals(ListingState.MISSING_ONCE,
                    listings.recordMissingObservation("listing-1", 3).join().orElseThrow().state());
            assertEquals(ListingState.MISSING_REPEATEDLY,
                    listings.recordMissingObservation("listing-1", 3).join().orElseThrow().state());
            assertEquals(ListingState.INACTIVE_UNKNOWN,
                    listings.recordMissingObservation("listing-1", 3).join().orElseThrow().state());

            assertEquals(ListingState.INACTIVE_UNKNOWN,
                    listings.recordMissingObservation("listing-1", 3).join().orElseThrow().state());
        }
    }

    @Test
    void fingerprintQueryAndInactiveRetentionAreBounded() {
        try (DatabaseManager database = database()) {
            FingerprintRepository fingerprints = new FingerprintRepository(database);
            ListingRepository listings = new ListingRepository(database);
            fingerprints.insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();
            listings.upsertBatch(List.of(
                    DatabaseTestFixtures.listing("old", BigDecimal.ONE, DatabaseTestFixtures.BASE_TIME),
                    DatabaseTestFixtures.listing("active", BigDecimal.TEN, DatabaseTestFixtures.BASE_TIME)
            )).join();

            assertEquals(2, listings.findByFingerprint(DatabaseTestFixtures.FINGERPRINT, 10).join().size());
            assertEquals(2, listings.clearStaleRawJsonBefore(
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(1), 10
            ).join());
            assertTrue(listings.find("active").join().orElseThrow().rawJson().isEmpty());
            assertTrue(listings.markState("old", ListingState.EXPIRED).join());
            assertEquals(List.of("active"), listings.findActiveByFingerprint(
                    DatabaseTestFixtures.FINGERPRINT, 10
            ).join().stream().map(ListingEntity::listingKey).toList());
            assertEquals(1, listings.deleteStaleInactiveBefore(
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(1), 10
            ).join());
            assertEquals(1L, listings.count().join());
            assertTrue(listings.find("active").join().isPresent());
        }
    }

    private DatabaseManager database() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("market-data.db"));
        database.ready().join();
        return database;
    }
}
