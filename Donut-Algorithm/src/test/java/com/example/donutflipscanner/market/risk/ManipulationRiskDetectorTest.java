package com.example.donutflipscanner.market.risk;

import com.example.donutflipscanner.market.confidence.ConfidenceCalculationRequest;
import com.example.donutflipscanner.market.confidence.ConfidenceCalculator;
import com.example.donutflipscanner.market.confidence.ConfidenceConfig;
import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.profit.CapitalLimits;
import com.example.donutflipscanner.market.profit.ProfitCalculator;
import com.example.donutflipscanner.market.profit.ProfitEvaluationConfig;
import com.example.donutflipscanner.market.profit.ProfitEvaluationRequest;
import com.example.donutflipscanner.market.profit.ProfitThresholds;
import com.example.donutflipscanner.market.profit.TradingCostConfig;
import com.example.donutflipscanner.market.value.ActiveAskEvidenceStatus;
import com.example.donutflipscanner.market.value.FairValueEstimate;
import com.example.donutflipscanner.market.value.FairValueExplanation;
import com.example.donutflipscanner.market.value.FairValueMarketContext;
import com.example.donutflipscanner.market.value.MarketTrend;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static com.example.donutflipscanner.market.risk.ManipulationRiskTestFixtures.COMMODITY;
import static com.example.donutflipscanner.market.risk.ManipulationRiskTestFixtures.SaleSpec;
import static com.example.donutflipscanner.market.risk.ManipulationRiskTestFixtures.context;
import static com.example.donutflipscanner.market.risk.ManipulationRiskTestFixtures.nearPrices;
import static com.example.donutflipscanner.market.risk.ManipulationRiskTestFixtures.normalContext;
import static com.example.donutflipscanner.market.risk.ManipulationRiskTestFixtures.oneSellerSales;
import static com.example.donutflipscanner.market.risk.ManipulationRiskTestFixtures.uniqueSales;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ManipulationRiskDetectorTest {
    private final ManipulationRiskDetector detector = new ManipulationRiskDetector();
    private final ManipulationRiskConfig config = ManipulationRiskConfig.defaults();

    @Test
    void normalMarketHasLowRiskAndNoIndicators() {
        ManipulationRiskAssessment result = assess(COMMODITY, normalContext(COMMODITY));

        assertEquals(MarketRiskLevel.LOW, result.riskLevel());
        assertEquals(0, result.riskScore());
        assertTrue(result.indicators().isEmpty());
        assertFalse(result.rejected());
        assertFalse(result.suppressSoundAlerts());
    }

    @Test
    void recordsOneExtremeHighSaleRemovedByRobustStatistics() {
        List<String> prices = new ArrayList<>(java.util.Collections.nCopies(7, "100"));
        prices.add("1000");
        FairValueMarketContext market = context(
                COMMODITY,
                uniqueSales(prices, Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(nearPrices(8), Duration.ofMinutes(1), Duration.ofMinutes(10)),
                uniqueSales(nearPrices(12), Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                8
        );

        ManipulationRiskAssessment result = assess(COMMODITY, market);

        assertIndicator(result, MarketAnomalyType.EXTREME_HIGH_SALES);
        assertEquals(MarketRiskLevel.MODERATE, result.riskLevel());
    }

    @Test
    void recordsOneExtremeLowSaleRemovedByRobustStatistics() {
        List<String> prices = new ArrayList<>(java.util.Collections.nCopies(7, "100"));
        prices.add("1");
        FairValueMarketContext market = context(
                COMMODITY,
                uniqueSales(prices, Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(nearPrices(8), Duration.ofMinutes(1), Duration.ofMinutes(10)),
                uniqueSales(nearPrices(12), Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                8
        );

        ManipulationRiskAssessment result = assess(COMMODITY, market);

        assertIndicator(result, MarketAnomalyType.EXTREME_LOW_SALES);
        assertEquals(MarketRiskLevel.MODERATE, result.riskLevel());
    }

    @Test
    void sellerDominatedMarketUsesNeutralConcentrationLanguage() {
        FairValueMarketContext market = context(
                COMMODITY,
                oneSellerSales(nearPrices(20)),
                uniqueSales(nearPrices(8), Duration.ofMinutes(1), Duration.ofMinutes(10)),
                uniqueSales(nearPrices(20), Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );

        ManipulationRiskAssessment result = assess(COMMODITY, market);

        MarketAnomalyIndicator indicator = indicator(result, MarketAnomalyType.SELLER_CONCENTRATION);
        assertEquals("Concentrated seller activity", indicator.label());
        assertTrue(indicator.explanation().contains("100%"));
        assertFalse(indicator.explanation().toLowerCase().contains("manipulator"));
    }

    @Test
    void suddenUnsupportedPriceSpikeBecomesSevere() {
        NormalizedItem approximate = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:future_market_item", 1)
        );
        assertEquals(ItemMatchType.APPROXIMATE, approximate.matchQuality().matchType());
        FairValueMarketContext market = context(
                approximate,
                uniqueSales(List.of("300", "300", "300"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(List.of("300", "300", "300"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(java.util.Collections.nCopies(8, "100"),
                        Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );

        ManipulationRiskAssessment result = assess(approximate, market);

        assertEquals(MarketRiskLevel.SEVERE, result.riskLevel());
        assertTrue(result.rejected());
        assertIndicator(result, MarketAnomalyType.SUDDEN_PRICE_JUMP);
        assertIndicator(result, MarketAnomalyType.LOW_VOLUME_PRICE_SPIKE);
        assertIndicator(result, MarketAnomalyType.MISSING_VALUE_METADATA);
    }

    @Test
    void toolDurabilityDoesNotAddAHiddenMetadataRiskPenalty() {
        NormalizedItem tool = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:diamond_pickaxe", 1)
        );

        ManipulationRiskAssessment result = assess(tool, normalContext(tool));

        assertEquals(ItemMatchType.VISIBLE_METADATA, tool.matchQuality().matchType());
        assertFalse(result.indicators().stream().anyMatch(value ->
                value.type() == MarketAnomalyType.MISSING_VALUE_METADATA));
    }

    @Test
    void legitimateHighVolumePriceTrendIsNotTreatedAsSevere() {
        FairValueMarketContext market = context(
                COMMODITY,
                uniqueSales(java.util.Collections.nCopies(20, "130"),
                        Duration.ofMinutes(1), Duration.ofMinutes(20)),
                uniqueSales(java.util.Collections.nCopies(10, "130"),
                        Duration.ofMinutes(1), Duration.ofMinutes(20)),
                uniqueSales(java.util.Collections.nCopies(20, "100"),
                        Duration.ofMinutes(1), Duration.ofHours(4)),
                List.of("128", "132"),
                100
        );

        ManipulationRiskAssessment result = assess(COMMODITY, market);

        assertEquals(MarketRiskLevel.LOW, result.riskLevel());
        assertFalse(result.rejected());
        assertIndicator(result, MarketAnomalyType.SUDDEN_PRICE_JUMP);
        assertFalse(hasIndicator(result, MarketAnomalyType.LOW_VOLUME_PRICE_SPIKE));
        assertFalse(hasIndicator(result, MarketAnomalyType.REPEATED_IDENTICAL_UNUSUAL_TRADES));
    }

    @Test
    void repeatedIdenticalUnusualTradesAreExplained() {
        FairValueMarketContext market = context(
                COMMODITY,
                uniqueSales(List.of("140", "140", "140"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(List.of("140", "140", "140"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(java.util.Collections.nCopies(8, "100"),
                        Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );

        ManipulationRiskAssessment result = assess(COMMODITY, market);

        MarketAnomalyIndicator indicator = indicator(
                result, MarketAnomalyType.REPEATED_IDENTICAL_UNUSUAL_TRADES
        );
        assertTrue(indicator.explanation().contains("3 completed sales"));
        assertTrue(indicator.explanation().contains("40%"));
    }

    @Test
    void circularAndRapidAlternatingPatternsAreDetectedWithoutAccusation() {
        List<SaleSpec> primary = List.of(
                new SaleSpec("100", "A", "B", Duration.ofMinutes(3)),
                new SaleSpec("100", "B", "A", Duration.ofMinutes(2)),
                new SaleSpec("100", "A", "B", Duration.ofMinutes(1))
        );
        FairValueMarketContext market = context(
                COMMODITY,
                primary,
                uniqueSales(nearPrices(3), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(nearPrices(8), Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );

        ManipulationRiskAssessment result = assess(COMMODITY, market);

        assertIndicator(result, MarketAnomalyType.CIRCULAR_TRADING_PATTERN);
        assertIndicator(result, MarketAnomalyType.RAPID_ALTERNATING_TRANSACTIONS);
        assertTrue(result.indicators().stream()
                .noneMatch(value -> value.explanation().toLowerCase().contains("cheat")));
    }

    @Test
    void commodityActiveAskCrossCheckFindsFarBelowSales() {
        FairValueMarketContext market = context(
                COMMODITY,
                uniqueSales(java.util.Collections.nCopies(8, "100"),
                        Duration.ofMinutes(1), Duration.ofMinutes(20)),
                uniqueSales(nearPrices(8), Duration.ofMinutes(1), Duration.ofMinutes(20)),
                uniqueSales(nearPrices(12), Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of("20", "25"),
                100
        );

        ManipulationRiskAssessment result = assess(COMMODITY, market);

        assertIndicator(result, MarketAnomalyType.ACTIVE_ASKS_FAR_BELOW_SALES);
    }

    @Test
    void commodityActiveAskCrossCheckFindsFarAboveSales() {
        FairValueMarketContext market = context(
                COMMODITY,
                uniqueSales(java.util.Collections.nCopies(8, "100"),
                        Duration.ofMinutes(1), Duration.ofMinutes(20)),
                uniqueSales(nearPrices(8), Duration.ofMinutes(1), Duration.ofMinutes(20)),
                uniqueSales(nearPrices(12), Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of("200", "210"),
                100
        );

        assertIndicator(assess(COMMODITY, market), MarketAnomalyType.ACTIVE_ASKS_FAR_ABOVE_SALES);
    }

    @Test
    void buyerConcentrationIsEvaluatedWhenBuyerDataExists() {
        List<SaleSpec> primary = new ArrayList<>();
        for (int index = 0; index < 10; index++) {
            primary.add(new SaleSpec("100", "seller-" + index, "buyer-one", Duration.ofMinutes(index + 1)));
        }
        FairValueMarketContext market = context(
                COMMODITY,
                primary,
                uniqueSales(nearPrices(8), Duration.ofMinutes(1), Duration.ofMinutes(10)),
                uniqueSales(nearPrices(12), Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );

        assertIndicator(assess(COMMODITY, market), MarketAnomalyType.BUYER_CONCENTRATION);
    }

    @Test
    void newlyObservedCustomVariantWithLittleHistoryIsFlagged() {
        NormalizedItem customDiamond = new ItemNormalizer().normalize(new ItemDescriptor(
                Optional.of("minecraft:diamond"), OptionalInt.of(1), Optional.of("Lucky Diamond"),
                List.of(), List.of(), Optional.empty(), List.of(), List.of()
        ));
        FairValueMarketContext market = context(
                customDiamond,
                uniqueSales(List.of("500", "500", "500"), Duration.ofMinutes(1), Duration.ofMinutes(10)),
                uniqueSales(List.of("500", "500", "500"), Duration.ofMinutes(1), Duration.ofMinutes(10)),
                uniqueSales(List.of("500", "500", "500"), Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );

        ManipulationRiskAssessment result = detector.assess(
                new ManipulationRiskRequest(customDiamond, market, Optional.of(Duration.ofHours(1))), config
        );

        assertIndicator(result, MarketAnomalyType.NEW_CUSTOM_ITEM_VARIANT);
    }

    @Test
    void emptyMarketHasUnknownRiskAndConservativeEffects() {
        FairValueMarketContext market = context(
                COMMODITY, List.of(), List.of(), List.of(), List.of(), 100
        );

        ManipulationRiskAssessment result = assess(COMMODITY, market);

        assertEquals(MarketRiskLevel.UNKNOWN, result.riskLevel());
        assertEquals(35, result.confidenceMaximumScore());
        assertEquals(10, result.confidenceReductionPoints());
        assertTrue(result.suppressSoundAlerts());
        assertFalse(result.rejected());
    }

    @Test
    void severeRiskReducesAndCapsConfidenceAndSuppressesSound() {
        NormalizedItem approximate = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:future_market_item", 1)
        );
        FairValueMarketContext market = context(
                approximate,
                uniqueSales(List.of("300", "300", "300"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(List.of("300", "300", "300"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(java.util.Collections.nCopies(8, "100"),
                        Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );
        ManipulationRiskAssessment risk = assess(approximate, market);

        var confidence = new ConfidenceCalculator().calculate(
                new ConfidenceCalculationRequest(
                        approximate,
                        market,
                        Duration.ofMinutes(1),
                        Duration.ZERO,
                        Optional.of(risk.confidenceConstraint())
                ),
                ConfidenceConfig.defaults()
        );

        assertEquals(25, risk.confidenceReductionPoints());
        assertEquals(25, risk.confidenceMaximumScore());
        assertTrue(confidence.scoreAfterAdjustments() <= confidence.rawScore() - 25);
        assertTrue(confidence.totalScore() <= 25);
        assertTrue(risk.suppressSoundAlerts());
        assertEquals(0, new java.math.BigDecimal("1.50").compareTo(risk.safetyBufferMultiplier()));
    }

    @Test
    void severeRiskProvidesExplicitRejectionExplanation() {
        NormalizedItem approximate = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:future_market_item", 1)
        );
        FairValueMarketContext market = context(
                approximate,
                uniqueSales(List.of("300", "300", "300"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(List.of("300", "300", "300"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(java.util.Collections.nCopies(8, "100"),
                        Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );

        ManipulationRiskAssessment result = assess(approximate, market);

        String explanation = result.rejectionExplanation().orElseThrow();
        assertTrue(explanation.startsWith("Rejected: severe market anomaly risk"));
        assertTrue(explanation.contains("Review required"));
        assertFalse(explanation.toLowerCase().contains("scammer"));
    }

    @Test
    void severeRiskSafetyGuidanceFlowsIntoProfitBuffer() {
        NormalizedItem approximate = new ItemNormalizer().normalize(
                ItemDescriptor.simple("minecraft:future_market_item", 1)
        );
        FairValueMarketContext market = context(
                approximate,
                uniqueSales(List.of("300", "300", "300"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(List.of("300", "300", "300"), Duration.ofMinutes(1), Duration.ofMinutes(5)),
                uniqueSales(java.util.Collections.nCopies(8, "100"),
                        Duration.ofMinutes(1), Duration.ofHours(2)),
                List.of(),
                100
        );
        ManipulationRiskAssessment risk = assess(approximate, market);
        BigDecimal fairAmount = new BigDecimal("1000");
        FairValueEstimate fairValue = new FairValueEstimate(
                approximate.fingerprint().sha256(), 1, false,
                Optional.of(fairAmount), Optional.of(fairAmount), Optional.of(fairAmount),
                Optional.of(fairAmount), Optional.of(fairAmount), MarketTrend.STABLE, true,
                BigDecimal.ONE, List.of(), new FairValueExplanation(
                8, 0, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), ActiveAskEvidenceStatus.NOT_AVAILABLE,
                Optional.empty(), List.of(), List.of())
        );
        FairValueEstimate riskAdjusted = risk.applySafetyBufferGuidance(fairValue);
        ProfitEvaluationConfig profitConfig = new ProfitEvaluationConfig(
                TradingCostConfig.defaults(), ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited()
        );
        var breakdown = new ProfitCalculator().evaluate(new ProfitEvaluationRequest(
                approximate.fingerprint().sha256(), new BigDecimal("600"), riskAdjusted,
                profitConfig, Optional.empty(), BigDecimal.ZERO
        )).breakdown().orElseThrow();

        assertEquals(0, new BigDecimal("1.50").compareTo(
                riskAdjusted.recommendedSafetyBufferMultiplier()));
        assertEquals(0, new BigDecimal("7.50").compareTo(breakdown.effectiveSafetyBufferPercent()));
        assertEquals(0, new BigDecimal("75").compareTo(breakdown.safetyBuffer()));
    }

    private ManipulationRiskAssessment assess(NormalizedItem item, FairValueMarketContext market) {
        return detector.assess(new ManipulationRiskRequest(item, market, Optional.empty()), config);
    }

    private static void assertIndicator(ManipulationRiskAssessment result, MarketAnomalyType type) {
        assertTrue(hasIndicator(result, type), "Missing indicator " + type);
    }

    private static boolean hasIndicator(ManipulationRiskAssessment result, MarketAnomalyType type) {
        return result.indicators().stream().anyMatch(indicator -> indicator.type() == type);
    }

    private static MarketAnomalyIndicator indicator(
            ManipulationRiskAssessment result,
            MarketAnomalyType type
    ) {
        return result.indicators().stream()
                .filter(indicator -> indicator.type() == type)
                .findFirst()
                .orElseThrow();
    }
}
