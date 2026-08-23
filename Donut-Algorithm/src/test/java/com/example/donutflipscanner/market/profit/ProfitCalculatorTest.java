package com.example.donutflipscanner.market.profit;

import com.example.donutflipscanner.market.value.FairValueEstimate;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static com.example.donutflipscanner.market.profit.ProfitTestFixtures.FINGERPRINT;
import static com.example.donutflipscanner.market.profit.ProfitTestFixtures.config;
import static com.example.donutflipscanner.market.profit.ProfitTestFixtures.fairValue;
import static com.example.donutflipscanner.market.profit.ProfitTestFixtures.noCosts;
import static com.example.donutflipscanner.market.profit.ProfitTestFixtures.request;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfitCalculatorTest {
    private final ProfitCalculator calculator = new ProfitCalculator();

    @Test
    void zeroFeesProduceGrossProfitAsNetProfit() {
        ProfitEvaluation result = calculator.evaluate(request(
                "600",
                fairValue("1000"),
                config(noCosts(), ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited())
        ));
        ProfitBreakdown breakdown = result.breakdown().orElseThrow();

        assertTrue(result.accepted());
        assertDecimal("400", breakdown.grossProfit());
        assertDecimal("400", breakdown.estimatedNetProfit());
        assertDecimal("0", breakdown.purchaseFee());
        assertDecimal("0", breakdown.saleFee());
        assertDecimal("66.66666666666666666666666666666667", breakdown.roiPercent().orElseThrow());
    }

    @Test
    void percentageFeesUsePurchaseAndConservativeSaleBases() {
        TradingCostConfig costs = new TradingCostConfig(
                new BigDecimal("10"), new BigDecimal("5"), BigDecimal.ZERO, BigDecimal.ZERO
        );

        ProfitBreakdown result = calculator.evaluate(request(
                "600", fairValue("1000"),
                config(costs, ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited())
        )).breakdown().orElseThrow();

        assertDecimal("60", result.purchaseFee());
        assertDecimal("50", result.saleFee());
        assertDecimal("290", result.estimatedNetProfit());
        assertDecimal("660", result.totalAcquisitionCost());
    }

    @Test
    void flatFeesReduceProfitAndCountTowardAcquisitionCost() {
        TradingCostConfig costs = new TradingCostConfig(
                BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("25"), BigDecimal.ZERO
        );

        ProfitBreakdown result = calculator.evaluate(request(
                "600", fairValue("1000"),
                config(costs, ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited())
        )).breakdown().orElseThrow();

        assertDecimal("25", result.flatCosts());
        assertDecimal("375", result.estimatedNetProfit());
        assertDecimal("625", result.totalAcquisitionCost());
    }

    @Test
    void safetyBufferUsesConfiguredAssumptionAndFairValueRiskMultiplier() {
        TradingCostConfig costs = new TradingCostConfig(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("5")
        );

        ProfitBreakdown result = calculator.evaluate(request(
                "600", fairValue("1000", "1.50"),
                config(costs, ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited())
        )).breakdown().orElseThrow();

        assertDecimal("5", result.configuredSafetyBufferPercent());
        assertDecimal("7.50", result.effectiveSafetyBufferPercent());
        assertDecimal("75", result.safetyBuffer());
        assertDecimal("325", result.estimatedNetProfit());
    }

    @Test
    void defaultsUseZeroFeesAndFivePercentUserAssumptionBuffer() {
        TradingCostConfig defaults = TradingCostConfig.defaults();

        assertDecimal("0", defaults.purchaseFeePercent());
        assertDecimal("0", defaults.saleFeePercent());
        assertDecimal("0", defaults.flatCost());
        assertDecimal("5", defaults.safetyBufferPercent());
    }

    @Test
    void negativeProfitFailsGrossNetAndRoiThresholds() {
        ProfitEvaluation result = calculator.evaluate(request(
                "1200", fairValue("1000"),
                config(noCosts(), ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited())
        ));

        assertFalse(result.accepted());
        assertDecimal("-200", result.breakdown().orElseThrow().estimatedNetProfit());
        assertCodes(result,
                ProfitRejectionCode.GROSS_PROFIT_BELOW_MINIMUM,
                ProfitRejectionCode.NET_PROFIT_BELOW_MINIMUM,
                ProfitRejectionCode.ROI_BELOW_MINIMUM);
    }

    @Test
    void calculatesRoiAgainstTotalAcquisitionCost() {
        ProfitBreakdown result = calculator.evaluate(request(
                "100", fairValue("150"),
                config(noCosts(), ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited())
        )).breakdown().orElseThrow();

        assertDecimal("50", result.estimatedNetProfit());
        assertDecimal("100", result.totalAcquisitionCost());
        assertDecimal("50", result.roiPercent().orElseThrow());
    }

    @Test
    void zeroPurchasePriceNeverDividesByZero() {
        ProfitEvaluation result = calculator.evaluate(request(
                "0", fairValue("100"),
                config(noCosts(), ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited())
        ));

        assertFalse(result.accepted());
        assertTrue(result.breakdown().orElseThrow().roiPercent().isEmpty());
        assertCodes(result,
                ProfitRejectionCode.PURCHASE_PRICE_NOT_POSITIVE,
                ProfitRejectionCode.ROI_UNAVAILABLE);
    }

    @Test
    void preservesVeryLargeValuesWithoutOverflow() {
        String fair = "99999999999999999999999999999999999999999999999999";
        String purchase = "11111111111111111111111111111111111111111111111111";

        ProfitBreakdown result = calculator.evaluate(request(
                purchase, fairValue(fair),
                config(noCosts(), ProfitThresholds.permissiveDefaults(), CapitalLimits.unlimited())
        )).breakdown().orElseThrow();

        assertDecimal("88888888888888888888888888888888888888888888888888", result.grossProfit());
        assertDecimal("88888888888888888888888888888888888888888888888888", result.estimatedNetProfit());
    }

    @Test
    void itemSpecificMinimumOverridesGlobalMinimum() {
        ProfitThresholds global = new ProfitThresholds(
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, Optional.empty()
        );
        ItemProfitThresholds item = new ItemProfitThresholds(
                Optional.of(new BigDecimal("500")), Optional.empty(), Optional.empty(), Optional.empty()
        );
        ProfitEvaluationRequest request = new ProfitEvaluationRequest(
                FINGERPRINT,
                new BigDecimal("600"),
                fairValue("1000"),
                config(noCosts(), global, CapitalLimits.unlimited()),
                Optional.of(item),
                BigDecimal.ZERO
        );

        ProfitEvaluation result = calculator.evaluate(request);

        assertFalse(result.accepted());
        assertTrue(result.thresholds().itemOverridesApplied());
        assertDecimal("500", result.thresholds().minimumGrossProfit());
        assertCodes(result, ProfitRejectionCode.GROSS_PROFIT_BELOW_MINIMUM);
    }

    @Test
    void itemMaximumRemainsSeparateFromGlobalMaximum() {
        ProfitThresholds global = new ProfitThresholds(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, Optional.of(new BigDecimal("1000"))
        );
        ItemProfitThresholds item = new ItemProfitThresholds(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(new BigDecimal("500"))
        );
        ProfitEvaluation result = calculator.evaluate(new ProfitEvaluationRequest(
                FINGERPRINT, new BigDecimal("600"), fairValue("1000"),
                config(noCosts(), global, CapitalLimits.unlimited()), Optional.of(item), BigDecimal.ZERO
        ));

        assertCodes(result, ProfitRejectionCode.ITEM_MAXIMUM_PURCHASE_PRICE_EXCEEDED);
    }

    @Test
    void maximumBankrollPercentageUsesOnlyManualAssumption() {
        CapitalLimits limits = new CapitalLimits(
                Optional.of(new BigDecimal("1000")),
                Optional.of(new BigDecimal("10")),
                Optional.empty()
        );
        ProfitEvaluation result = calculator.evaluate(request(
                "110", fairValue("200"),
                config(noCosts(), ProfitThresholds.permissiveDefaults(), limits)
        ));

        assertCodes(result, ProfitRejectionCode.BANKROLL_PERCENTAGE_LIMIT_EXCEEDED);
        assertTrue(result.rejections().getFirst().message().contains("configured bankroll allocation 100"));
    }

    @Test
    void projectedExposureIncludesExistingOpenExposure() {
        CapitalLimits limits = new CapitalLimits(
                Optional.empty(), Optional.empty(), Optional.of(new BigDecimal("500"))
        );
        ProfitEvaluation result = calculator.evaluate(new ProfitEvaluationRequest(
                FINGERPRINT, new BigDecimal("100"), fairValue("200"),
                config(noCosts(), ProfitThresholds.permissiveDefaults(), limits),
                Optional.empty(), new BigDecimal("450")
        ));

        assertDecimal("550", result.breakdown().orElseThrow().projectedOpenExposure());
        assertCodes(result, ProfitRejectionCode.OPEN_EXPOSURE_LIMIT_EXCEEDED);
    }

    @Test
    void reportsEveryIndependentRejectionReason() {
        ProfitThresholds global = new ProfitThresholds(
                new BigDecimal("100"),
                new BigDecimal("100"),
                new BigDecimal("20"),
                Optional.of(new BigDecimal("500"))
        );
        ItemProfitThresholds item = new ItemProfitThresholds(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(new BigDecimal("600"))
        );
        CapitalLimits limits = new CapitalLimits(
                Optional.empty(), Optional.empty(), Optional.of(new BigDecimal("900"))
        );
        ProfitEvaluation result = calculator.evaluate(new ProfitEvaluationRequest(
                FINGERPRINT, new BigDecimal("1000"), fairValue("900"),
                config(noCosts(), global, limits), Optional.of(item), BigDecimal.ZERO
        ));

        assertCodes(result,
                ProfitRejectionCode.GROSS_PROFIT_BELOW_MINIMUM,
                ProfitRejectionCode.NET_PROFIT_BELOW_MINIMUM,
                ProfitRejectionCode.ROI_BELOW_MINIMUM,
                ProfitRejectionCode.GLOBAL_MAXIMUM_PURCHASE_PRICE_EXCEEDED,
                ProfitRejectionCode.ITEM_MAXIMUM_PURCHASE_PRICE_EXCEEDED,
                ProfitRejectionCode.OPEN_EXPOSURE_LIMIT_EXCEEDED);
        assertTrue(result.rejections().stream()
                .filter(rejection -> rejection.code() == ProfitRejectionCode.ROI_BELOW_MINIMUM)
                .findFirst().orElseThrow().message().contains("is below required 20%"));
    }

    @Test
    void unavailableFairValueStopsFinancialCalculation() {
        ProfitEvaluation result = calculator.evaluate(request(
                "100",
                ProfitTestFixtures.insufficientFairValue(),
                ProfitEvaluationConfig.defaults()
        ));

        assertFalse(result.accepted());
        assertTrue(result.breakdown().isEmpty());
        assertCodes(result, ProfitRejectionCode.FAIR_VALUE_UNAVAILABLE);
    }

    private static void assertCodes(ProfitEvaluation evaluation, ProfitRejectionCode... expected) {
        assertEquals(List.of(expected), evaluation.rejections().stream().map(ProfitRejection::code).toList());
    }

    private static void assertDecimal(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }
}
