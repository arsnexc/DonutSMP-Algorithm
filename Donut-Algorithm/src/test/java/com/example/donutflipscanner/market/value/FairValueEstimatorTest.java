package com.example.donutflipscanner.market.value;

import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.MarketLookbackPeriod;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static com.example.donutflipscanner.market.value.FairValueTestFixtures.COMMODITY;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.STABLE_LONG_TERM;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.STABLE_PRIMARY;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.context;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.stableContext;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.statistics;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FairValueEstimatorTest {
    private final FairValueEstimator estimator = new FairValueEstimator();
    private final FairValueConfig config = FairValueConfig.defaults();

    @Test
    void estimatesStableMarketFromCompletedSalesAndCredibleAsk() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY, stableContext(COMMODITY, List.of("103", "104")), config
        );

        assertTrue(estimate.sufficientData());
        assertEquals(MarketTrend.STABLE, estimate.trend());
        assertDecimal("100", estimate.conservativeValue().orElseThrow());
        assertDecimal("101", estimate.centralValue().orElseThrow());
        assertDecimal("104.25", estimate.optimisticValue().orElseThrow());
        assertEquals(ActiveAskEvidenceStatus.CREDIBLE, estimate.explanation().activeAskStatus());
        assertTrue(estimate.explanation().adjustments().isEmpty());
    }

    @Test
    void detectsRisingMarketWithoutExtrapolatingItUpward() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY,
                context(COMMODITY, STABLE_PRIMARY, List.of("125", "126", "127"),
                        List.of("99", "100", "101"), List.of()),
                config
        );

        assertEquals(MarketTrend.RISING, estimate.trend());
        assertTrue(estimate.warnings().contains(FairValueWarning.RAPID_RISE));
        assertDecimal("100", estimate.conservativeValue().orElseThrow());
        assertDecimal("1.50", estimate.recommendedSafetyBufferMultiplier());
        assertTrue(estimate.explanation().notes().stream().anyMatch(note -> note.contains("not extrapolated")));
    }

    @Test
    void fallingMarketReducesConservativeValue() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY,
                context(COMMODITY, STABLE_PRIMARY, List.of("80", "80", "80"),
                        List.of("99", "100", "101"), List.of()),
                config
        );

        assertEquals(MarketTrend.FALLING, estimate.trend());
        assertDecimal("95", estimate.conservativeValue().orElseThrow());
        assertDecimal("101", estimate.centralValue().orElseThrow());
        assertTrue(estimate.warnings().contains(FairValueWarning.FALLING_MARKET));
        assertDecimal("1.25", estimate.recommendedSafetyBufferMultiplier());
    }

    @Test
    void ignoresArtificiallyHighSecondAsk() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY, stableContext(COMMODITY, List.of("103", "10000")), config
        );

        assertEquals(ActiveAskEvidenceStatus.REJECTED_ABOVE_COMPLETED_RANGE,
                estimate.explanation().activeAskStatus());
        assertTrue(estimate.warnings().contains(FairValueWarning.ACTIVE_ASK_ABOVE_COMPLETED_RANGE));
        assertDecimal("100", estimate.conservativeValue().orElseThrow());
    }

    @Test
    void secondLowestStrategyIgnoresOneArtificiallyLowAsk() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY, stableContext(COMMODITY, List.of("1", "103")), config
        );

        assertEquals(ActiveAskEvidenceStatus.CREDIBLE, estimate.explanation().activeAskStatus());
        assertDecimal("103", estimate.explanation().consideredSecondLowestAsk().orElseThrow());
        assertDecimal("100", estimate.conservativeValue().orElseThrow());
    }

    @Test
    void rejectsSecondAskWhenLowAskEvidenceIsStillImplausible() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY, stableContext(COMMODITY, List.of("1", "2")), config
        );

        assertEquals(ActiveAskEvidenceStatus.REJECTED_BELOW_COMPLETED_RANGE,
                estimate.explanation().activeAskStatus());
        assertTrue(estimate.warnings().contains(FairValueWarning.ACTIVE_ASK_BELOW_COMPLETED_RANGE));
        assertDecimal("100", estimate.conservativeValue().orElseThrow());
    }

    @Test
    void oneAskDoesNotSubstituteForMissingSecondAsk() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY, stableContext(COMMODITY, List.of("90")), config
        );

        assertEquals(ActiveAskEvidenceStatus.NOT_AVAILABLE, estimate.explanation().activeAskStatus());
        assertDecimal("100", estimate.conservativeValue().orElseThrow());
    }

    @Test
    void fewCompletedSalesReturnNoFabricatedFairValues() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY,
                context(COMMODITY, List.of("100", "102", "104"),
                        List.of("100", "102", "104"), STABLE_LONG_TERM, List.of()),
                config
        );

        assertFalse(estimate.sufficientData());
        assertTrue(estimate.conservativeValue().isEmpty());
        assertTrue(estimate.centralValue().isEmpty());
        assertTrue(estimate.optimisticValue().isEmpty());
        assertTrue(estimate.warnings().contains(FairValueWarning.LOW_DATA));
        assertTrue(estimate.explanation().notes().getFirst().contains("minimum required: 8"));
    }

    @Test
    void highVolatilityAppliesDownwardAdjustment() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY,
                context(COMMODITY,
                        List.of("50", "60", "70", "100", "130", "160", "200", "250"),
                        List.of("99", "100", "101"), STABLE_LONG_TERM, List.of()),
                config
        );

        assertTrue(estimate.warnings().contains(FairValueWarning.HIGH_VOLATILITY));
        assertTrue(estimate.explanation().adjustments().stream()
                .anyMatch(adjustment -> adjustment.code().equals("HIGH_VOLATILITY")));
        assertTrue(estimate.conservativeValue().orElseThrow().compareTo(new BigDecimal("88")) < 0);
        assertDecimal("1.50", estimate.recommendedSafetyBufferMultiplier());
    }

    @Test
    void highActiveSupplyReducesConservativeValue() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY,
                stableContext(COMMODITY, java.util.Collections.nCopies(9, "103")),
                config
        );

        assertTrue(estimate.warnings().contains(FairValueWarning.HIGH_ACTIVE_SUPPLY));
        assertDecimal("95", estimate.conservativeValue().orElseThrow());
    }

    @Test
    void commodityEstimateIsScaledToTargetStackCount() {
        NormalizedItem stackOfFour = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:netherite_ingot", 4)
        );
        FairValueEstimate estimate = estimator.estimate(
                stackOfFour, stableContext(stackOfFour, List.of()), config
        );

        assertTrue(estimate.unitPriceBased());
        assertEquals(4, estimate.targetItemCount());
        assertDecimal("400", estimate.conservativeValue().orElseThrow());
        assertDecimal("404", estimate.centralValue().orElseThrow());
        assertDecimal("380", estimate.observedLow().orElseThrow());
    }

    @Test
    void exactCustomizedItemUsesWholeListingPrices() {
        NormalizedItem customDiamond = customDiamond();
        assertEquals(ItemMatchType.EXACT, customDiamond.matchQuality().matchType());
        FairValueEstimate estimate = estimator.estimate(
                customDiamond,
                context(customDiamond,
                        java.util.Collections.nCopies(8, "500"),
                        java.util.Collections.nCopies(3, "500"),
                        java.util.Collections.nCopies(3, "500"),
                        List.of()),
                config
        );

        assertFalse(estimate.unitPriceBased());
        assertEquals(1, estimate.targetItemCount());
        assertDecimal("500", estimate.conservativeValue().orElseThrow());
        assertDecimal("500", estimate.centralValue().orElseThrow());
    }

    @Test
    void conservativeEstimateNeverExceedsCentralEstimate() {
        FairValueEstimate estimate = estimator.estimate(
                COMMODITY, stableContext(COMMODITY, List.of("80", "90")), config
        );

        assertTrue(estimate.conservativeValue().orElseThrow()
                .compareTo(estimate.centralValue().orElseThrow()) <= 0);
    }

    @Test
    void stalePrimaryWindowRefusesToProduceFairValue() {
        ItemMarketStatistics stalePrimary = statistics(
                COMMODITY, STABLE_PRIMARY, List.of(), MarketLookbackPeriod.THREE_DAYS, Duration.ofHours(7)
        );
        FairValueMarketContext market = new FairValueMarketContext(
                stalePrimary,
                statistics(COMMODITY, List.of(), List.of(), MarketLookbackPeriod.SIX_HOURS, Duration.ZERO),
                statistics(COMMODITY, STABLE_LONG_TERM, List.of(), MarketLookbackPeriod.THIRTY_DAYS,
                        Duration.ofHours(7))
        );

        FairValueEstimate estimate = estimator.estimate(COMMODITY, market, config);

        assertFalse(estimate.sufficientData());
        assertTrue(estimate.warnings().contains(FairValueWarning.STALE_MARKET));
        assertTrue(estimate.conservativeValue().isEmpty());
    }

    private static NormalizedItem customDiamond() {
        return new ItemNormalizer().normalize(new ItemDescriptor(
                Optional.of("minecraft:diamond"),
                OptionalInt.of(1),
                Optional.of("Lucky Diamond"),
                List.of(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of()
        ));
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
