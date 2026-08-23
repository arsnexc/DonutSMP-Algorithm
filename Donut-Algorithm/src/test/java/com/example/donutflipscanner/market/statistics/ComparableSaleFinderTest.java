package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.model.ComparableSaleRejectionReason;
import com.example.donutflipscanner.market.statistics.model.ComparableSaleSet;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.example.donutflipscanner.market.statistics.MarketStatisticsTestFixtures.COMMODITY;
import static com.example.donutflipscanner.market.statistics.MarketStatisticsTestFixtures.NOW;
import static com.example.donutflipscanner.market.statistics.MarketStatisticsTestFixtures.sale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComparableSaleFinderTest {
    private final ComparableSaleFinder finder = new ComparableSaleFinder();

    @Test
    void rejectsMismatchesOutsideWindowFutureRowsAndNonPositivePrices() {
        SaleEntity mismatch = withFingerprint(
                sale("mismatch", BigDecimal.TEN, 1, Duration.ofHours(1)), "f".repeat(64)
        );
        SaleEntity old = sale("old", BigDecimal.TEN, 1, Duration.ofDays(8));
        SaleEntity future = sale("future", BigDecimal.TEN, 1, Duration.ofHours(-1));
        SaleEntity zero = sale("zero", BigDecimal.ZERO, 1, Duration.ofHours(1));

        ComparableSaleSet result = finder.find(
                COMMODITY, List.of(mismatch, old, future, zero),
                MarketStatisticsTestFixtures.config(1, 8), NOW
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(List.of(
                        ComparableSaleRejectionReason.FINGERPRINT_MISMATCH,
                        ComparableSaleRejectionReason.OUTSIDE_LOOKBACK,
                        ComparableSaleRejectionReason.FUTURE_TIMESTAMP,
                        ComparableSaleRejectionReason.INVALID_PRICE
                ),
                result.rejected().stream().map(rejection -> rejection.reason()).toList());
    }

    @Test
    void unsupportedItemsNeverProduceComparableHistory() {
        NormalizedItem unsupported = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:player_head", 1)
        );
        SaleEntity raw = withFingerprint(
                sale("book", BigDecimal.TEN, 1, Duration.ofHours(1)),
                unsupported.fingerprint().sha256()
        );

        ComparableSaleSet result = finder.find(
                unsupported, List.of(raw), MarketStatisticsTestFixtures.config(1, 8), NOW
        );

        assertTrue(result.accepted().isEmpty());
        assertEquals(ComparableSaleRejectionReason.UNSUPPORTED_ITEM, result.rejected().getFirst().reason());
    }

    private static SaleEntity withFingerprint(SaleEntity source, String fingerprint) {
        return new SaleEntity(
                source.saleKey(), source.remoteTransactionId(), source.sellerUuid(), source.sellerName(),
                source.buyerUuid(), source.buyerName(), fingerprint, source.rawItemId(), source.itemCount(),
                source.salePrice(), source.unitPrice(), source.soldAt(), source.importedAt(), Optional.empty()
        );
    }
}
