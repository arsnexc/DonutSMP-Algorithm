package com.example.donutflipscanner.market.opportunity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpportunityThresholdsTest {
    @Test
    void liveDefaultDisplaysOpportunitiesAtTenPercentConfidence() {
        assertEquals(10, OpportunityThresholds.defaults().minimumConfidence());
    }

    @Test
    void relaxedLiveProfileHalvesConfidenceAndComparableSaleBarriers() {
        assertEquals(5, OpportunityThresholds.relaxedLiveDefaults().minimumConfidence());
        assertEquals(4, OpportunityThresholds.relaxedLiveDefaults().minimumComparableSales());
        assertEquals(4, OpportunityEvaluationConfig.relaxedLiveDefaults()
                .fairValueConfig().minimumCompletedSales());
        assertEquals(2, OpportunityEvaluationConfig.relaxedLiveDefaults()
                .fairValueConfig().minimumTrendSales());
    }
}
