package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.profit.ItemProfitThresholds;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static com.example.donutflipscanner.market.opportunity.OpportunityTestFixtures.ITEM;
import static com.example.donutflipscanner.market.opportunity.OpportunityTestFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityEvaluatorTest {
    private final OpportunityEvaluator evaluator = new OpportunityEvaluator();

    @Test
    void acceptsStrongFlipWithFullExplanationAndAlertEligibility() {
        OpportunityEvaluation result = evaluator.evaluate(
                OpportunityTestFixtures.request("50"),
                OpportunityTestFixtures.config(ItemFilterPolicy.allowAll(),
                        OpportunityTestFixtures.thresholds("20", "30")),
                NOW
        );

        assertTrue(result.accepted());
        assertEquals(OpportunityState.NEW, result.state());
        assertTrue(result.highPriorityAlertEligible());
        assertTrue(result.explanation().fairValue().isPresent());
        assertTrue(result.explanation().profit().isPresent());
        assertTrue(result.explanation().confidence().isPresent());
        assertTrue(result.explanation().riskAssessment().isPresent());
        assertEquals(40, result.explanation().acceptedComparableSales());
        assertTrue(result.explanation().passedChecks().size() >= 7);
    }

    @Test
    void rejectsWhenMinimumProfitFails() {
        OpportunityEvaluation result = evaluate("50", OpportunityTestFixtures.thresholds("60", "0"));

        assertFalse(result.accepted());
        assertHas(result, OpportunityRejectionCode.PROFIT_REQUIREMENT_FAILED);
    }

    @Test
    void rejectsWhenMinimumRoiFails() {
        OpportunityEvaluation result = evaluate("50", OpportunityTestFixtures.thresholds("0", "110"));

        assertFalse(result.accepted());
        assertHas(result, OpportunityRejectionCode.PROFIT_REQUIREMENT_FAILED);
    }

    @Test
    void rejectsWhenConfidenceThresholdFails() {
        OpportunityEvaluationConfig config = OpportunityTestFixtures.config(
                ItemFilterPolicy.allowAll(), OpportunityTestFixtures.thresholds("0", "0"),
                new OpportunityThresholds(99, 8, MarketRiskLevel.MODERATE),
                "config-v1", "filters-v1", java.time.Duration.ofMinutes(1)
        );
        OpportunityEvaluation result = evaluator.evaluate(OpportunityTestFixtures.request("50"), config, NOW);

        assertFalse(result.accepted());
        assertHas(result, OpportunityRejectionCode.CONFIDENCE_BELOW_MINIMUM);
    }

    @Test
    void enforcesBlacklistAndWhitelistModes() {
        ItemFilterPolicy blacklist = new ItemFilterPolicy(
                ItemFilterMode.ALL_EXCEPT_BLACKLIST, Set.of(), Set.of(ITEM.itemId())
        );
        OpportunityEvaluation blacklisted = evaluator.evaluate(
                OpportunityTestFixtures.request("50"),
                OpportunityTestFixtures.config(blacklist, OpportunityTestFixtures.thresholds("0", "0")), NOW
        );
        ItemFilterPolicy whitelist = new ItemFilterPolicy(
                ItemFilterMode.WHITELIST_ONLY, Set.of("minecraft:diamond"), Set.of()
        );
        OpportunityEvaluation notWhitelisted = evaluator.evaluate(
                OpportunityTestFixtures.request("50"),
                OpportunityTestFixtures.config(whitelist, OpportunityTestFixtures.thresholds("0", "0")), NOW
        );

        assertEquals(OpportunityState.REJECTED_BY_FILTER, blacklisted.state());
        assertHas(blacklisted, OpportunityRejectionCode.ITEM_BLACKLISTED);
        assertHas(notWhitelisted, OpportunityRejectionCode.ITEM_NOT_WHITELISTED);
    }

    @Test
    void rejectsOverlappingWhitelistAndBlacklistConfiguration() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () ->
                new ItemFilterPolicy(ItemFilterMode.ALL_ITEMS, Set.of(ITEM.itemId()), Set.of(ITEM.itemId()))
        );
    }

    @Test
    void itemSpecificThresholdOverridesGlobalThreshold() {
        ItemEvaluationProfile profile = new ItemEvaluationProfile(
                true,
                new ItemProfitThresholds(
                        Optional.empty(), Optional.of(new BigDecimal("60")),
                        Optional.empty(), Optional.empty()
                ),
                OptionalInt.empty(), OptionalInt.empty()
        );
        OpportunityEvaluationRequest request = OpportunityTestFixtures.request(
                ITEM, OpportunityTestFixtures.stableMarket(ITEM),
                OpportunityTestFixtures.listing("override", ITEM, "50", ListingState.ACTIVE),
                Optional.of(profile), 1, false
        );
        OpportunityEvaluation result = evaluator.evaluate(
                request,
                OpportunityTestFixtures.config(ItemFilterPolicy.allowAll(),
                        OpportunityTestFixtures.thresholds("0", "0")),
                NOW
        );

        assertFalse(result.accepted());
        assertTrue(result.explanation().itemOverridesApplied());
        assertTrue(result.explanation().profit().orElseThrow().thresholds().itemOverridesApplied());
    }

    @Test
    void disabledItemProfileProducesNoOpportunity() {
        ItemEvaluationProfile profile = new ItemEvaluationProfile(
                false, ItemProfitThresholds.none(), OptionalInt.empty(), OptionalInt.empty()
        );
        OpportunityEvaluation result = evaluator.evaluate(
                OpportunityTestFixtures.request(
                        ITEM, OpportunityTestFixtures.stableMarket(ITEM),
                        OpportunityTestFixtures.listing("disabled", ITEM, "50", ListingState.ACTIVE),
                        Optional.of(profile), 1, false
                ),
                OpportunityTestFixtures.config(ItemFilterPolicy.allowAll(),
                        OpportunityTestFixtures.thresholds("0", "0")), NOW
        );

        assertFalse(result.accepted());
        assertHas(result, OpportunityRejectionCode.ITEM_PROFILE_DISABLED);
    }

    @Test
    void unsupportedItemIsRejectedBeforeValuation() {
        NormalizedItem unsupported = OpportunityTestFixtures.withMatchType(ITEM, ItemMatchType.UNSUPPORTED);
        OpportunityEvaluation result = evaluator.evaluate(
                OpportunityTestFixtures.request(
                        unsupported, OpportunityTestFixtures.stableMarket(unsupported),
                        OpportunityTestFixtures.listing("unsupported", unsupported, "50", ListingState.ACTIVE),
                        Optional.empty(), 1, false
                ),
                OpportunityTestFixtures.config(ItemFilterPolicy.allowAll(),
                        OpportunityTestFixtures.thresholds("0", "0")), NOW
        );

        assertFalse(result.accepted());
        assertHas(result, OpportunityRejectionCode.ITEM_MATCH_UNSUPPORTED);
        assertTrue(result.explanation().fairValue().isEmpty());
    }

    @Test
    void severeMarketRiskRejectsOpportunity() {
        NormalizedItem riskyItem = OpportunityTestFixtures.CUSTOM_ITEM;
        OpportunityEvaluationRequest base = OpportunityTestFixtures.request(
                riskyItem, OpportunityTestFixtures.severeRiskMarket(riskyItem),
                OpportunityTestFixtures.listing("risky", riskyItem, "50", ListingState.ACTIVE),
                Optional.empty(), 1, false
        );
        OpportunityEvaluationRequest request = new OpportunityEvaluationRequest(
                base.listing(), base.item(), base.market(), base.itemProfile(), base.currentOpenExposure(),
                base.marketSnapshotAge(), Optional.of(Duration.ofHours(1)), base.completedSalesVersion(), false
        );
        OpportunityEvaluation result = evaluator.evaluate(
                request,
                OpportunityTestFixtures.config(ItemFilterPolicy.allowAll(),
                        OpportunityTestFixtures.thresholds("0", "0")), NOW
        );

        assertEquals(MarketRiskLevel.SEVERE,
                result.explanation().riskAssessment().orElseThrow().riskLevel());
        assertHas(result, OpportunityRejectionCode.SEVERE_MARKET_RISK);
        assertFalse(result.highPriorityAlertEligible());
    }

    @Test
    void expiredListingGetsTerminalState() {
        OpportunityEvaluation result = evaluator.evaluate(
                OpportunityTestFixtures.request(
                        ITEM, OpportunityTestFixtures.stableMarket(ITEM),
                        OpportunityTestFixtures.listing("expired", ITEM, "50", ListingState.EXPIRED),
                        Optional.empty(), 1, false
                ),
                OpportunityTestFixtures.config(ItemFilterPolicy.allowAll(),
                        OpportunityTestFixtures.thresholds("0", "0")), NOW
        );

        assertEquals(OpportunityState.EXPIRED, result.state());
        assertHas(result, OpportunityRejectionCode.LISTING_EXPIRED);
    }

    private OpportunityEvaluation evaluate(String price, com.example.donutflipscanner.market.profit.ProfitThresholds thresholds) {
        return evaluator.evaluate(
                OpportunityTestFixtures.request(price),
                OpportunityTestFixtures.config(ItemFilterPolicy.allowAll(), thresholds), NOW
        );
    }

    private static void assertHas(OpportunityEvaluation evaluation, OpportunityRejectionCode code) {
        assertTrue(evaluation.explanation().rejections().stream().anyMatch(reason -> reason.code() == code));
    }
}
