package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.profit.PersonalProfitSnapshot;
import com.example.donutflipscanner.profit.PlayerIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PersonalProfitRepositoryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void realizesGrossProfitOnlyAfterConfirmedPurchaseAndMatchingPlayerSale() {
        try (DatabaseManager database = preparedDatabase()) {
            PersonalProfitRepository profits = new PersonalProfitRepository(database);
            Instant purchaseTime = DatabaseTestFixtures.BASE_TIME.plusSeconds(40);

            assertTrue(profits.confirmPurchase(
                    "opportunity-1", purchaseTime, DatabaseTestFixtures.BASE_TIME.plusSeconds(25)
            ).join());
            assertEquals("PURCHASED_MANUALLY", new OpportunityRepository(database)
                    .find("opportunity-1").join().orElseThrow().state());

            SaleEntity otherPlayer = sale(
                    "other-sale", "another-player", "SomeoneElse",
                    new BigDecimal("20800000"), purchaseTime.plusSeconds(30), 4
            );
            SaleEntity exactPlayerSale = sale(
                    "player-sale", "AABBCCDD-0000-0000-0000-000000000000", "CurrentPlayer",
                    new BigDecimal("20800000"), purchaseTime.plusSeconds(60), 4
            );
            PlayerIdentity identity = new PlayerIdentity(
                    "CurrentPlayer", Optional.of("aabbccdd000000000000000000000000")
            );

            assertEquals(1, profits.reconcileSales(identity, List.of(otherPlayer, exactPlayerSale)).join());
            assertEquals(0, profits.reconcileSales(identity, List.of(exactPlayerSale)).join());

            PersonalProfitSnapshot snapshot = profits.snapshot(purchaseTime.plusSeconds(61)).join();
            assertEquals(new BigDecimal("6600000"), snapshot.realizedProfit());
            assertEquals(new BigDecimal("14200000"), snapshot.realizedAcquisitionCost());
            assertEquals(new BigDecimal("20800000"), snapshot.saleProceeds());
            assertEquals(0, snapshot.openPositions());
            assertEquals(1, snapshot.realizedTrades());
            assertEquals(new BigDecimal("6600000"), snapshot.points().getFirst().cumulativeProfit());
        }
    }

    @Test
    void rejectsStaleOpportunityAndDoesNotMatchWrongStackCount() {
        try (DatabaseManager database = preparedDatabase()) {
            PersonalProfitRepository profits = new PersonalProfitRepository(database);
            Instant purchaseTime = DatabaseTestFixtures.BASE_TIME.plusSeconds(40);

            assertFalse(profits.confirmPurchase(
                    "opportunity-1", purchaseTime, DatabaseTestFixtures.BASE_TIME.plusSeconds(41)
            ).join());
            assertTrue(profits.confirmPurchase(
                    "opportunity-1", purchaseTime, DatabaseTestFixtures.BASE_TIME.plusSeconds(25)
            ).join());
            SaleEntity wrongCount = sale(
                    "wrong-count", "player-uuid", "CurrentPlayer",
                    new BigDecimal("20800000"), purchaseTime.plusSeconds(60), 1
            );

            assertEquals(0, profits.reconcileSales(
                    new PlayerIdentity("CurrentPlayer", Optional.of("player-uuid")),
                    List.of(wrongCount)
            ).join());
            assertEquals(1, profits.snapshot(purchaseTime.plusSeconds(61)).join().openPositions());
        }
    }

    private DatabaseManager preparedDatabase() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("market-data.db"));
        database.ready().join();
        new FingerprintRepository(database).insertIfAbsent(DatabaseTestFixtures.fingerprint()).join();
        new ListingRepository(database).upsertBatch(List.of(DatabaseTestFixtures.listing(
                "listing-1", new BigDecimal("14200000"),
                DatabaseTestFixtures.BASE_TIME.plusSeconds(40)
        ))).join();
        OpportunityEntity opportunity = DatabaseTestFixtures.opportunity("listing-1");
        new OpportunityRepository(database).upsert(opportunity).join();
        return database;
    }

    private static SaleEntity sale(
            String saleKey,
            String sellerUuid,
            String sellerName,
            BigDecimal price,
            Instant soldAt,
            int count
    ) {
        return new SaleEntity(
                saleKey, Optional.empty(), Optional.of(sellerUuid), Optional.of(sellerName),
                Optional.empty(), Optional.empty(), DatabaseTestFixtures.FINGERPRINT,
                "minecraft:netherite_ingot", count, price,
                Optional.of(price.divide(BigDecimal.valueOf(count))), soldAt,
                soldAt.plusSeconds(1), Optional.empty()
        );
    }
}
