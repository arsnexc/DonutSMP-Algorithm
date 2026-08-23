package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;
import com.example.donutflipscanner.market.statistics.model.MarketDataStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static com.example.donutflipscanner.market.statistics.MarketStatisticsTestFixtures.COMMODITY;
import static com.example.donutflipscanner.market.statistics.MarketStatisticsTestFixtures.NOW;
import static com.example.donutflipscanner.market.statistics.MarketStatisticsTestFixtures.config;
import static com.example.donutflipscanner.market.statistics.MarketStatisticsTestFixtures.listing;
import static com.example.donutflipscanner.market.statistics.MarketStatisticsTestFixtures.sale;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarketStatisticsCalculatorTest {
    private final MarketStatisticsCalculator calculator = new MarketStatisticsCalculator();

    @Test
    void commodityComparablePricesAreNormalizedPerUnit() {
        ItemMarketStatistics result = calculate(
                List.of(sale("stack", new BigDecimal("400"), 4, Duration.ofMinutes(5))),
                List.of(),
                config(1, 8)
        );

        assertDecimal("100", result.median().orElseThrow());
        assertEquals(1, result.comparableSaleCount());
        assertEquals(MarketDataStatus.SUFFICIENT, result.status());
    }

    @Test
    void smallSamplesAreNotFilteredAndAreClearlyLowData() {
        List<SaleEntity> sales = List.of(
                sale("one", new BigDecimal("100"), 1, Duration.ofMinutes(1)),
                sale("two", new BigDecimal("100"), 1, Duration.ofMinutes(2)),
                sale("extreme", new BigDecimal("10000"), 1, Duration.ofMinutes(3))
        );

        ItemMarketStatistics result = calculate(sales, List.of(), config(8, 8));

        assertEquals(3, result.comparableSaleCount());
        assertEquals(0, result.excludedSaleCount());
        assertTrue(result.lowData());
        assertEquals(MarketDataStatus.LOW_DATA, result.status());
        assertTrue(result.dataQualityExplanation().contains("Only 3 comparable sales found"));
    }

    @Test
    void rejectsExtremeHighAndLowPricesWhenSampleIsLargeEnough() {
        List<BigDecimal> values = List.of(
                new BigDecimal("1"),
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("100"), new BigDecimal("100"), new BigDecimal("100"),
                new BigDecimal("1000")
        );
        List<SaleEntity> sales = new ArrayList<>();
        for (int index = 0; index < values.size(); index++) {
            sales.add(sale("sale-" + index, values.get(index), 1, Duration.ofMinutes(index + 1)));
        }

        ItemMarketStatistics result = calculate(sales, List.of(), config(1, 8));

        assertEquals(6, result.comparableSaleCount());
        assertEquals(2, result.excludedSaleCount());
        assertDecimal("100", result.minimum().orElseThrow());
        assertDecimal("100", result.maximum().orElseThrow());
        assertTrue(result.comparableSales().rejected().stream()
                .allMatch(rejection -> rejection.explanation().contains("IQR")));
    }

    @Test
    void rejectsOneExtremeHighOutlierWithMad() {
        List<SaleEntity> sales = salesAtUnitPrices("high", "90", "95", "100", "100", "105", "110", "115", "1000");

        ItemMarketStatistics result = calculate(sales, List.of(), config(1, 8));

        assertEquals(7, result.comparableSaleCount());
        assertEquals(1, result.excludedSaleCount());
        assertEquals(com.example.donutflipscanner.market.statistics.model.ComparableSaleRejectionReason.MAD_OUTLIER,
                result.comparableSales().rejected().getFirst().reason());
        assertDecimal("115", result.maximum().orElseThrow());
    }

    @Test
    void rejectsOneExtremeLowOutlierWithMad() {
        List<SaleEntity> sales = salesAtUnitPrices("low", "1", "90", "95", "100", "100", "105", "110", "115");

        ItemMarketStatistics result = calculate(sales, List.of(), config(1, 8));

        assertEquals(7, result.comparableSaleCount());
        assertEquals(1, result.excludedSaleCount());
        assertEquals(com.example.donutflipscanner.market.statistics.model.ComparableSaleRejectionReason.MAD_OUTLIER,
                result.comparableSales().rejected().getFirst().reason());
        assertDecimal("90", result.minimum().orElseThrow());
    }

    @Test
    void preservesBalancedMultiplePriceClusters() {
        List<SaleEntity> sales = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            sales.add(sale("low-" + index, new BigDecimal("100"), 1, Duration.ofMinutes(index + 1)));
            sales.add(sale("high-" + index, new BigDecimal("200"), 1, Duration.ofMinutes(index + 5)));
        }

        ItemMarketStatistics result = calculate(sales, List.of(), config(8, 8));

        assertEquals(8, result.comparableSaleCount());
        assertEquals(0, result.excludedSaleCount());
        assertDecimal("150", result.median().orElseThrow());
    }

    @Test
    void missingActiveAsksProduceAnEmptyAskSnapshot() {
        ItemMarketStatistics result = calculate(
                List.of(sale("sale", new BigDecimal("100"), 1, Duration.ofMinutes(1))),
                List.of(),
                config(1, 8)
        );

        assertEquals(0, result.activeAsks().listingCount());
        assertTrue(result.activeAsks().lowestAsk().isEmpty());
        assertTrue(result.activeAsks().largestSellerListingSharePercent().isEmpty());
    }

    @Test
    void activeAsksIncludeSupplyAndSellerConcentrationButExcludeInactiveRows() {
        List<ListingEntity> listings = List.of(
                listing("a-1", new BigDecimal("100"), 1, "seller-a", ListingState.ACTIVE),
                listing("a-2", new BigDecimal("160"), 2, "seller-a", ListingState.ACTIVE),
                listing("b-1", new BigDecimal("360"), 3, "seller-b", ListingState.ACTIVE),
                listing("expired", new BigDecimal("1"), 1, "seller-c", ListingState.EXPIRED)
        );

        ItemMarketStatistics result = calculate(List.of(), listings, config(1, 8));

        assertEquals(3, result.activeAsks().listingCount());
        assertEquals(6, result.activeAsks().totalSupplyQuantity());
        assertEquals(2, result.activeAsks().uniqueSellerCount());
        assertDecimal("80", result.activeAsks().lowestAsk().orElseThrow());
        assertDecimal("100", result.activeAsks().secondLowestAsk().orElseThrow());
        assertDecimal("100", result.activeAsks().medianAsk().orElseThrow());
        assertDecimal("66.66666666666666666666666666666667",
                result.activeAsks().largestSellerListingSharePercent().orElseThrow());
    }

    @Test
    void sufficientlyLargeButOldHistoryIsMarkedStale() {
        ItemMarketStatistics result = calculate(
                List.of(
                        sale("one", new BigDecimal("100"), 1, Duration.ofHours(12)),
                        sale("two", new BigDecimal("110"), 1, Duration.ofHours(13))
                ),
                List.of(),
                config(2, 8)
        );

        assertFalse(result.lowData());
        assertTrue(result.stale());
        assertEquals(MarketDataStatus.STALE, result.status());
        assertEquals(Duration.ofHours(12), result.timeSinceMostRecentSale().orElseThrow());
    }

    @Test
    void computesAllRequestedDescriptiveStatisticsAndRates() {
        List<SaleEntity> sales = List.of(
                sale("one", new BigDecimal("10"), 1, Duration.ofHours(1)),
                sale("two", new BigDecimal("20"), 1, Duration.ofHours(2)),
                sale("three", new BigDecimal("30"), 1, Duration.ofHours(3)),
                sale("four", new BigDecimal("40"), 1, Duration.ofHours(4))
        );

        ItemMarketStatistics result = calculate(sales, List.of(), config(4, 8));

        assertDecimal("10", result.minimum().orElseThrow());
        assertDecimal("40", result.maximum().orElseThrow());
        assertDecimal("25", result.mean().orElseThrow());
        assertDecimal("25", result.median().orElseThrow());
        assertDecimal("17.5", result.percentile25().orElseThrow());
        assertDecimal("22", result.percentile40().orElseThrow());
        assertDecimal("32.5", result.percentile75().orElseThrow());
        assertDecimal("15", result.interquartileRange().orElseThrow());
        assertDecimal("10", result.medianAbsoluteDeviation().orElseThrow());
        assertDecimal("0.02380952380952380952380952380952381", result.salesPerHour().orElseThrow());
        assertDecimal("0.5714285714285714285714285714285714", result.salesPerDay().orElseThrow());
        assertEquals(4, result.uniqueSellerCount());
        assertEquals(4, result.uniqueBuyerCount());
    }

    private ItemMarketStatistics calculate(
            List<SaleEntity> sales,
            List<ListingEntity> listings,
            MarketStatisticsConfig config
    ) {
        return calculator.calculate(COMMODITY, sales, listings, config, NOW);
    }

    private static List<SaleEntity> salesAtUnitPrices(String prefix, String... prices) {
        List<SaleEntity> sales = new ArrayList<>();
        for (int index = 0; index < prices.length; index++) {
            sales.add(sale(
                    prefix + "-" + index,
                    new BigDecimal(prices[index]),
                    1,
                    Duration.ofMinutes(index + 1)
            ));
        }
        return List.copyOf(sales);
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
