package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.SaleEntity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SaleRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void batchInsertionIgnoresConfirmedDuplicatesAndPreservesHugePrices() {
        try (DatabaseManager database = database()) {
            FingerprintRepository fingerprints = new FingerprintRepository(database);
            SaleRepository sales = new SaleRepository(database);
            fingerprints.insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();

            BigDecimal hugePrice = new BigDecimal("99999999999999999999999999999999999999999999999999");
            SaleEntity first = DatabaseTestFixtures.sale(
                    "sale-1", DatabaseTestFixtures.FINGERPRINT, hugePrice, DatabaseTestFixtures.BASE_TIME
            );
            SaleEntity second = DatabaseTestFixtures.sale(
                    "sale-2", DatabaseTestFixtures.FINGERPRINT, new BigDecimal("20800000"),
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(1)
            );

            BatchWriteResult initial = sales.insertBatch(List.of(first, second)).join();
            BatchWriteResult duplicate = sales.insertBatch(List.of(first)).join();

            assertEquals(2, initial.inserted());
            assertEquals(0, duplicate.inserted());
            assertEquals(1, duplicate.updatedOrIgnored());
            assertEquals(2L, sales.count().join());
            assertEquals(hugePrice, sales.find("sale-1").join().orElseThrow().salePrice());
            assertEquals(2, sales.findByFingerprint(DatabaseTestFixtures.FINGERPRINT, 10).join().size());
        }
    }

    @Test
    void foreignKeyFailureRollsBackTheEntireBatch() {
        try (DatabaseManager database = database()) {
            FingerprintRepository fingerprints = new FingerprintRepository(database);
            SaleRepository sales = new SaleRepository(database);
            fingerprints.insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();

            SaleEntity valid = DatabaseTestFixtures.sale(
                    "sale-valid", DatabaseTestFixtures.FINGERPRINT, BigDecimal.TEN, Instant.EPOCH
            );
            SaleEntity invalidForeignKey = DatabaseTestFixtures.sale(
                    "sale-invalid", "missing-fingerprint", BigDecimal.ONE, Instant.EPOCH.plusSeconds(1)
            );

            CompletionException failure = assertThrows(
                    CompletionException.class,
                    () -> sales.insertBatch(List.of(valid, invalidForeignKey)).join()
            );
            assertInstanceOf(DatabaseException.class, failure.getCause());
            assertEquals(0L, sales.count().join());
        }
    }

    @Test
    void completedSalesSurviveDatabaseRestart() {
        Path path = temporaryDirectory.resolve("market-data.db");
        try (DatabaseManager firstDatabase = new DatabaseManager(path)) {
            firstDatabase.ready().join();
            new FingerprintRepository(firstDatabase).insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();
            new SaleRepository(firstDatabase).insertBatch(List.of(DatabaseTestFixtures.sale(
                    "persistent-sale",
                    DatabaseTestFixtures.FINGERPRINT,
                    new BigDecimal("123456789"),
                    DatabaseTestFixtures.BASE_TIME
            ))).join();
        }

        try (DatabaseManager reopenedDatabase = new DatabaseManager(path)) {
            reopenedDatabase.ready().join();
            SaleEntity restored = new SaleRepository(reopenedDatabase)
                    .find("persistent-sale").join().orElseThrow();
            assertEquals(new BigDecimal("123456789"), restored.salePrice());
            assertEquals(5, reopenedDatabase.schemaVersion().join());
        }
    }

    @Test
    void statisticsQueryAppliesBothTimeBounds() {
        try (DatabaseManager database = database()) {
            FingerprintRepository fingerprints = new FingerprintRepository(database);
            SaleRepository sales = new SaleRepository(database);
            fingerprints.insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();
            sales.insertBatch(List.of(
                    DatabaseTestFixtures.sale("too-old", DatabaseTestFixtures.FINGERPRINT,
                            BigDecimal.ONE, DatabaseTestFixtures.BASE_TIME.minusSeconds(7_200)),
                    DatabaseTestFixtures.sale("included", DatabaseTestFixtures.FINGERPRINT,
                            BigDecimal.TEN, DatabaseTestFixtures.BASE_TIME),
                    DatabaseTestFixtures.sale("future", DatabaseTestFixtures.FINGERPRINT,
                            BigDecimal.valueOf(100), DatabaseTestFixtures.BASE_TIME.plusSeconds(3_600))
            )).join();

            List<SaleEntity> result = sales.findByFingerprintBetween(
                    DatabaseTestFixtures.FINGERPRINT,
                    Optional.of(DatabaseTestFixtures.BASE_TIME.minusSeconds(3_600)),
                    DatabaseTestFixtures.BASE_TIME.plusSeconds(60),
                    10
            ).join();

            assertEquals(List.of("included"), result.stream().map(SaleEntity::saleKey).toList());
        }
    }

    private DatabaseManager database() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("market-data.db"));
        database.ready().join();
        return database;
    }
}
