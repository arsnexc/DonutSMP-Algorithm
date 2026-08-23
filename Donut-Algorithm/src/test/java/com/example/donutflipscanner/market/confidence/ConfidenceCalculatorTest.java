package com.example.donutflipscanner.market.confidence;

import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.value.FairValueMarketContext;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.example.donutflipscanner.market.confidence.ConfidenceTestFixtures.COMMODITY;
import static com.example.donutflipscanner.market.confidence.ConfidenceTestFixtures.context;
import static com.example.donutflipscanner.market.confidence.ConfidenceTestFixtures.highQualityContext;
import static com.example.donutflipscanner.market.confidence.ConfidenceTestFixtures.nearPrices;
import static com.example.donutflipscanner.market.confidence.ConfidenceTestFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfidenceCalculatorTest {
    private final ConfidenceCalculator calculator = new ConfidenceCalculator();
    private final ConfidenceConfig config = ConfidenceConfig.defaults();

    @Test
    void highConfidenceCommodityUsesStrongCompletedSaleEvidence() {
        ConfidenceBreakdown result = calculator.calculate(
                request(COMMODITY, highQualityContext(COMMODITY, false)), config
        );

        assertTrue(result.totalScore() >= 90);
        assertEquals(25, result.comparableSalesScore());
        assertEquals(15, result.sellerDiversityScore());
        assertEquals(20, result.priceStabilityScore());
        assertEquals(15, result.itemMatchScore());
        assertEquals(15, result.liquidityScore());
        assertEquals(5, result.freshnessScore());
        assertTrue(result.caps().isEmpty());
    }

    @Test
    void lowSampleCountReceivesScoreAndHardCap() {
        FairValueMarketContext market = context(
                COMMODITY,
                List.of("100", "101"),
                List.of("100", "101"),
                List.of("100", "101"),
                Duration.ofMinutes(1),
                Duration.ofMinutes(30),
                false
        );

        ConfidenceBreakdown result = calculator.calculate(request(COMMODITY, market), config);

        assertEquals(3, result.comparableSalesScore());
        assertTrue(result.totalScore() <= 35);
        assertCap(result, ConfidenceCapReason.LOW_SAMPLE, 35);
        assertWarning(result, ConfidenceWarningCode.LOW_SAMPLE);
    }

    @Test
    void sellerDominatedMarketLosesDiversityPoints() {
        ConfidenceBreakdown diverse = calculator.calculate(
                request(COMMODITY, highQualityContext(COMMODITY, false)), config
        );
        ConfidenceBreakdown concentrated = calculator.calculate(
                request(COMMODITY, highQualityContext(COMMODITY, true)), config
        );

        assertTrue(concentrated.sellerDiversityScore() <= 2);
        assertTrue(concentrated.sellerDiversityScore() < diverse.sellerDiversityScore());
        assertWarning(concentrated, ConfidenceWarningCode.SELLER_CONCENTRATION);
    }

    @Test
    void extremeVolatilityLowersStabilityAndCapsConfidence() {
        List<String> volatilePrices = List.of("1", "1", "1", "2", "50", "200", "500", "1000");
        FairValueMarketContext market = context(
                COMMODITY,
                volatilePrices,
                List.of("100", "101", "99"),
                List.of("100", "101", "99"),
                Duration.ofMinutes(1),
                Duration.ofMinutes(30),
                false
        );

        ConfidenceBreakdown result = calculator.calculate(request(COMMODITY, market), config);

        assertTrue(result.priceStabilityScore() < 10);
        assertTrue(result.totalScore() <= 50);
        assertCap(result, ConfidenceCapReason.EXTREME_VOLATILITY, 50);
        assertWarning(result, ConfidenceWarningCode.HIGH_VOLATILITY);
    }

    @Test
    void approximateItemMatchHasEightBasePointsAndCap() {
        NormalizedItem approximate = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:future_market_item", 1)
        );
        assertEquals(ItemMatchType.APPROXIMATE, approximate.matchQuality().matchType());

        ConfidenceBreakdown result = calculator.calculate(
                request(approximate, highQualityContext(approximate, false)), config
        );

        assertEquals(8, result.itemMatchScore());
        assertTrue(result.totalScore() <= 60);
        assertCap(result, ConfidenceCapReason.APPROXIMATE_ITEM, 60);
        assertWarning(result, ConfidenceWarningCode.APPROXIMATE_ITEM_MATCH);
    }

    @Test
    void visibleMetadataItemHasGuardedMatchPointsAndCap() {
        NormalizedItem visibleMetadata = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:enchanted_book", 1)
        );
        assertEquals(ItemMatchType.VISIBLE_METADATA, visibleMetadata.matchQuality().matchType());

        ConfidenceBreakdown result = calculator.calculate(
                request(visibleMetadata, highQualityContext(visibleMetadata, false)), config
        );

        assertEquals(12, result.itemMatchScore());
        assertTrue(result.totalScore() <= 60);
        assertCap(result, ConfidenceCapReason.APPROXIMATE_ITEM, 60);
        assertWarning(result, ConfidenceWarningCode.APPROXIMATE_ITEM_MATCH);
    }

    @Test
    void unsupportedItemHasZeroMatchPointsAndTwentyPointCap() {
        NormalizedItem unsupported = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:player_head", 1)
        );
        assertEquals(ItemMatchType.UNSUPPORTED, unsupported.matchQuality().matchType());

        ConfidenceBreakdown result = calculator.calculate(
                request(unsupported, highQualityContext(unsupported, false)), config
        );

        assertEquals(0, result.itemMatchScore());
        assertTrue(result.totalScore() <= 20);
        assertCap(result, ConfidenceCapReason.UNSUPPORTED_ITEM, 20);
    }

    @Test
    void staleMarketLosesFreshnessPointsAndExplainsWhy() {
        FairValueMarketContext market = context(
                COMMODITY,
                nearPrices(8),
                List.of(),
                nearPrices(8),
                Duration.ofHours(12),
                Duration.ofHours(1),
                false
        );
        ConfidenceCalculationRequest request = new ConfidenceCalculationRequest(
                COMMODITY,
                market,
                Duration.ofDays(2),
                Duration.ofMinutes(20),
                Optional.empty()
        );

        ConfidenceBreakdown result = calculator.calculate(request, config);

        assertTrue(result.freshnessScore() <= 1);
        assertWarning(result, ConfidenceWarningCode.STALE_MARKET);
    }

    @Test
    void lowCompletedSaleActivityProducesLowLiquidity() {
        FairValueMarketContext market = context(
                COMMODITY,
                nearPrices(8),
                List.of(),
                nearPrices(8),
                Duration.ofHours(1),
                Duration.ofHours(10),
                false
        );

        ConfidenceBreakdown result = calculator.calculate(request(COMMODITY, market), config);

        assertTrue(result.liquidityScore() < 8);
        assertWarning(result, ConfidenceWarningCode.LOW_LIQUIDITY);
        assertTrue(result.categories().stream()
                .filter(category -> category.category() == ConfidenceCategory.LIQUIDITY)
                .flatMap(category -> category.evidence().stream())
                .anyMatch(line -> line.contains("supply evidence")));
    }

    @Test
    void externalRiskCapCanPrepareForLaterManipulationStage() {
        ConfidenceCalculationRequest request = new ConfidenceCalculationRequest(
                COMMODITY,
                highQualityContext(COMMODITY, false),
                Duration.ofMinutes(1),
                Duration.ZERO,
                Optional.of(new ExternalConfidenceCap(
                        "SEVERE_MARKET_RISK", 25, "Later anomaly analysis supplied a severe-risk cap."
                ))
        );

        ConfidenceBreakdown result = calculator.calculate(request, config);

        assertTrue(result.rawScore() >= 90);
        assertEquals(25, result.totalScore());
        assertCap(result, ConfidenceCapReason.EXTERNAL_RISK, 25);
        assertWarning(result, ConfidenceWarningCode.EXTERNAL_RISK);
    }

    @Test
    void everyScoreRemainsInsideZeroToOneHundred() {
        List<ConfidenceCalculationRequest> requests = List.of(
                request(COMMODITY, highQualityContext(COMMODITY, false)),
                request(COMMODITY, highQualityContext(COMMODITY, true)),
                request(COMMODITY, context(COMMODITY, List.of(), List.of(), List.of(),
                        Duration.ZERO, Duration.ZERO, false))
        );

        for (ConfidenceCalculationRequest request : requests) {
            ConfidenceBreakdown result = calculator.calculate(request, config);
            assertTrue(result.rawScore() >= 0 && result.rawScore() <= 100);
            assertTrue(result.totalScore() >= 0 && result.totalScore() <= 100);
        }
    }

    @Test
    void configurableWeightsMustStillTotalOneHundred() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConfidenceWeights(25, 15, 20, 20, 15, 4));
        ConfidenceWeights valid = new ConfidenceWeights(20, 20, 20, 20, 10, 10);
        ConfidenceConfig custom = new ConfidenceConfig(valid, 3, 35, 60, 20,
                new java.math.BigDecimal("0.75"), 50);

        ConfidenceBreakdown result = calculator.calculate(
                request(COMMODITY, highQualityContext(COMMODITY, false)), custom
        );

        assertEquals(100, valid.comparableSales() + valid.sellerDiversity() + valid.priceStability()
                + valid.itemMatch() + valid.liquidity() + valid.freshness());
        assertTrue(result.totalScore() <= 100);
    }

    @Test
    void tooltipAdapterExposesScoreCategoriesCapsAndWarnings() {
        ConfidenceCalculationRequest request = new ConfidenceCalculationRequest(
                COMMODITY,
                highQualityContext(COMMODITY, false),
                Duration.ofMinutes(1),
                Duration.ZERO,
                Optional.of(new ExternalConfidenceCap("TEST", 25, "Test risk cap."))
        );
        ConfidenceBreakdown result = calculator.calculate(request, config);

        List<String> lines = ConfidenceTooltipFormatter.lines(result);

        assertEquals("Confidence: 25%", lines.getFirst());
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Comparable sales: ")));
        assertTrue(lines.stream().anyMatch(line -> line.equals("Cap 25%: TEST: Test risk cap.")));
        assertTrue(lines.stream().anyMatch(line -> line.startsWith("Warning: ")));
    }

    private static void assertCap(ConfidenceBreakdown result, ConfidenceCapReason reason, int maximum) {
        AppliedConfidenceCap cap = result.caps().stream()
                .filter(candidate -> candidate.reason() == reason)
                .findFirst()
                .orElseThrow();
        assertEquals(maximum, cap.maximumScore());
    }

    private static void assertWarning(ConfidenceBreakdown result, ConfidenceWarningCode code) {
        assertTrue(result.warnings().stream().anyMatch(warning -> warning.code() == code));
    }
}
