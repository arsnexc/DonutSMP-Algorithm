package com.example.donutflipscanner.market.profit;

import com.example.donutflipscanner.market.value.ActiveAskEvidenceStatus;
import com.example.donutflipscanner.market.value.FairValueEstimate;
import com.example.donutflipscanner.market.value.FairValueExplanation;
import com.example.donutflipscanner.market.value.FairValueWarning;
import com.example.donutflipscanner.market.value.MarketTrend;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

final class ProfitTestFixtures {
    static final String FINGERPRINT = "a".repeat(64);

    private ProfitTestFixtures() {
    }

    static FairValueEstimate fairValue(String value) {
        return fairValue(value, "1.00");
    }

    static FairValueEstimate fairValue(String value, String safetyMultiplier) {
        BigDecimal amount = new BigDecimal(value);
        return new FairValueEstimate(
                FINGERPRINT,
                1,
                false,
                Optional.of(amount),
                Optional.of(amount),
                Optional.of(amount),
                Optional.of(amount),
                Optional.of(amount),
                MarketTrend.STABLE,
                true,
                new BigDecimal(safetyMultiplier),
                List.of(),
                explanation(8)
        );
    }

    static FairValueEstimate insufficientFairValue() {
        return new FairValueEstimate(
                FINGERPRINT,
                1,
                false,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(BigDecimal.TEN),
                Optional.of(BigDecimal.valueOf(20)),
                MarketTrend.UNKNOWN,
                false,
                BigDecimal.ONE,
                List.of(FairValueWarning.LOW_DATA),
                explanation(2)
        );
    }

    static ProfitEvaluationRequest request(
            String purchasePrice,
            FairValueEstimate fairValue,
            ProfitEvaluationConfig config
    ) {
        return new ProfitEvaluationRequest(
                FINGERPRINT,
                new BigDecimal(purchasePrice),
                fairValue,
                config,
                Optional.empty(),
                BigDecimal.ZERO
        );
    }

    static ProfitEvaluationConfig config(
            TradingCostConfig costs,
            ProfitThresholds thresholds,
            CapitalLimits capitalLimits
    ) {
        return new ProfitEvaluationConfig(costs, thresholds, capitalLimits);
    }

    static TradingCostConfig noCosts() {
        return new TradingCostConfig(
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
        );
    }

    private static FairValueExplanation explanation(int acceptedSales) {
        return new FairValueExplanation(
                acceptedSales,
                0,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                ActiveAskEvidenceStatus.NOT_AVAILABLE,
                Optional.empty(),
                List.of(),
                List.of()
        );
    }
}
