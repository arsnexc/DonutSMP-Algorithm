package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.RelistPricingStrategy;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.service.RelistPricingService;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RelistPricingServiceTest {
    @Test
    void targetRoiIsBoundedByConservativeFairValue() {
        AutomationConfig config = config(RelistPricingStrategy.TARGET_ROI, "200", "0");
        assertEquals(new BigDecimal("66500"), new RelistPricingService().plan(request(), config).listingPrice());
    }

    @Test
    void fixedMarkupUsesWholeCurrencyUnits() {
        AutomationConfig config = config(RelistPricingStrategy.FIXED_MARKUP, "0", "20.125");
        assertEquals(new BigDecimal("30031"), new RelistPricingService().plan(request(), config).listingPrice());
    }

    @Test
    void impossibleProfitFloorFailsClosed() {
        AutomationConfig config = new AutomationConfig(
                false, AutomationMode.DRY_RUN, Set.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0,
                30, 8, new BigDecimal("50000"), BigDecimal.ZERO, 30, 0,
                RelistPricingStrategy.CONSERVATIVE_FAIR_VALUE, BigDecimal.ZERO, BigDecimal.ZERO,
                true, true, true, true
        );
        assertThrows(IllegalArgumentException.class, () -> new RelistPricingService().plan(request(), config));
    }

    private static AutomationConfig config(RelistPricingStrategy strategy, String target, String markup) {
        return new AutomationConfig(
                false, AutomationMode.DRY_RUN, Set.of(), BigDecimal.ZERO, BigDecimal.ZERO, 0,
                30, 8, BigDecimal.ZERO, BigDecimal.ZERO, 30, 0,
                strategy, new BigDecimal(target), new BigDecimal(markup),
                true, true, true, true
        );
    }

    private static TradeExecutionRequest request() {
        return new TradeExecutionRequest(
                "execution", "opportunity", "listing", "fingerprint", "minecraft:diamond_leggings",
                1, Optional.empty(), new BigDecimal("25000"), new BigDecimal("66500"),
                new BigDecimal("37000"), new BigDecimal("149"), 60, 8, MarketRiskLevel.LOW,
                Instant.parse("2026-08-04T11:59:58Z"), new BigDecimal("25000"), 30,
                AutomationMode.DRY_RUN
        );
    }
}
