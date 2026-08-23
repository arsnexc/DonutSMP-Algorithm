package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.database.entity.ListingState;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static com.example.donutflipscanner.market.opportunity.OpportunityTestFixtures.ITEM;
import static com.example.donutflipscanner.market.opportunity.OpportunityTestFixtures.NOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OpportunityDetectorTest {
    private final OpportunityEvaluationConfig config = OpportunityTestFixtures.config(
            ItemFilterPolicy.allowAll(), OpportunityTestFixtures.thresholds("20", "30")
    );

    @Test
    void suppressesUnchangedDuplicateAndKeepsStableIdWhenPriceChanges() {
        OpportunityDetector detector = new OpportunityDetector();
        OpportunityEvaluationRequest original = OpportunityTestFixtures.request("50");
        OpportunityDetectionResult first = detector.evaluate(original, config, NOW);
        OpportunityDetectionResult duplicate = detector.evaluate(original, config, NOW.plusSeconds(1));
        OpportunityEvaluationRequest repriced = OpportunityTestFixtures.request(
                ITEM, OpportunityTestFixtures.stableMarket(ITEM),
                OpportunityTestFixtures.listing("listing-one", ITEM, "45", ListingState.ACTIVE),
                Optional.empty(), 1, false
        );
        OpportunityDetectionResult changed = detector.evaluate(repriced, config, NOW.plusSeconds(2));

        assertTrue(first.evaluation().isPresent());
        assertTrue(duplicate.duplicateUnchangedListing());
        assertTrue(duplicate.evaluation().isEmpty());
        assertTrue(changed.reevaluation().reasons().contains(ReevaluationReason.LISTING_PRICE_CHANGED));
        assertEquals(first.evaluation().orElseThrow().opportunityId(),
                changed.evaluation().orElseThrow().opportunityId());
    }

    @Test
    void reevaluatesWhenCompletedSalesChange() {
        OpportunityDetector detector = new OpportunityDetector();
        OpportunityEvaluationRequest request = OpportunityTestFixtures.request("50");
        detector.evaluate(request, config, NOW);

        OpportunityDetectionResult result = detector.evaluate(
                OpportunityTestFixtures.withSalesVersion(request, 2), config, NOW.plusSeconds(2)
        );

        assertTrue(result.reevaluation().shouldEvaluate());
        assertTrue(result.reevaluation().reasons().contains(ReevaluationReason.COMPLETED_SALES_CHANGED));
    }

    @Test
    void reevaluatesWhenConfigurationOrFiltersChange() {
        OpportunityDetector detector = new OpportunityDetector();
        OpportunityEvaluationRequest request = OpportunityTestFixtures.request("50");
        detector.evaluate(request, config, NOW);
        OpportunityEvaluationConfig changedConfig = OpportunityTestFixtures.config(
                ItemFilterPolicy.allowAll(), OpportunityTestFixtures.thresholds("20", "30"),
                config.thresholds(), "config-v2", "filters-v1", Duration.ofMinutes(1)
        );

        OpportunityDetectionResult configurationResult = detector.evaluate(
                request, changedConfig, NOW.plusSeconds(2)
        );
        OpportunityEvaluationConfig changedFilters = OpportunityTestFixtures.config(
                ItemFilterPolicy.allowAll(), OpportunityTestFixtures.thresholds("20", "30"),
                config.thresholds(), "config-v2", "filters-v2", Duration.ofMinutes(1)
        );
        OpportunityDetectionResult filterResult = detector.evaluate(
                request, changedFilters, NOW.plusSeconds(4)
        );

        assertTrue(configurationResult.reevaluation().reasons()
                .contains(ReevaluationReason.CONFIGURATION_CHANGED));
        assertTrue(filterResult.reevaluation().reasons().contains(ReevaluationReason.FILTERS_CHANGED));
    }

    @Test
    void changedEvaluationRespectsAlertCooldown() {
        OpportunityDetector detector = new OpportunityDetector();
        OpportunityEvaluationRequest request = OpportunityTestFixtures.request("50");
        OpportunityEvaluation first = detector.evaluate(request, config, NOW).evaluation().orElseThrow();
        assertTrue(detector.recordAlerted(first.opportunityId(), NOW));

        OpportunityEvaluation second = detector.evaluate(
                OpportunityTestFixtures.withSalesVersion(request, 2), config, NOW.plusSeconds(10)
        ).evaluation().orElseThrow();

        assertTrue(second.accepted());
        assertFalse(second.highPriorityAlertEligible());
        assertTrue(second.alertSuppressions().contains(AlertSuppressionReason.ALERT_COOLDOWN_ACTIVE));
    }

    @Test
    void dismissedOpportunityStaysDismissedAfterReevaluation() {
        OpportunityDetector detector = new OpportunityDetector();
        OpportunityEvaluationRequest request = OpportunityTestFixtures.request("50");
        OpportunityEvaluation first = detector.evaluate(request, config, NOW).evaluation().orElseThrow();
        assertTrue(detector.updateState(first.opportunityId(), OpportunityState.DISMISSED));

        OpportunityEvaluation second = detector.evaluate(
                OpportunityTestFixtures.withSalesVersion(request, 2), config, NOW.plusSeconds(2)
        ).evaluation().orElseThrow();

        assertEquals(OpportunityState.DISMISSED, second.state());
        assertTrue(second.accepted());
        assertFalse(second.highPriorityAlertEligible());
        assertTrue(second.alertSuppressions().contains(AlertSuppressionReason.OPPORTUNITY_DISMISSED));
    }

    @Test
    void restoredTrackingStateSuppressesRestartAlertDuringCooldown() {
        OpportunityDetector firstDetector = new OpportunityDetector();
        OpportunityEvaluationRequest request = OpportunityTestFixtures.request("50");
        OpportunityEvaluation first = firstDetector.evaluate(request, config, NOW).evaluation().orElseThrow();
        assertTrue(firstDetector.recordAlerted(first.opportunityId(), NOW));
        OpportunityTrackingSnapshot saved = firstDetector.snapshots().getFirst();

        OpportunityDetector restarted = new OpportunityDetector();
        restarted.restore(saved);
        OpportunityEvaluationRequest restartRequest = OpportunityTestFixtures.request(
                request.item(), request.market(), request.listing(), request.itemProfile(),
                request.completedSalesVersion(), true
        );
        OpportunityDetectionResult result = restarted.evaluate(restartRequest, config, NOW.plusSeconds(10));

        assertTrue(result.reevaluation().reasons().contains(ReevaluationReason.ACTIVE_LISTING_AFTER_RESTART));
        assertFalse(result.evaluation().orElseThrow().highPriorityAlertEligible());
        assertTrue(result.evaluation().orElseThrow().alertSuppressions()
                .contains(AlertSuppressionReason.ALERT_COOLDOWN_ACTIVE));
    }

    @Test
    void evaluationVersionChangesOpportunityIdentity() {
        OpportunityIdFactory factory = new OpportunityIdFactory();

        String first = factory.create("listing", "opportunity-v1", ITEM.fingerprint().sha256());
        String second = factory.create("listing", "opportunity-v2", ITEM.fingerprint().sha256());

        assertNotEquals(first, second);
        assertEquals(first, factory.create("listing", "opportunity-v1", ITEM.fingerprint().sha256()));
    }

    @Test
    void staleDataTriggersEvaluationWithoutEveryFramePolling() {
        OpportunityDetector detector = new OpportunityDetector();
        OpportunityEvaluationConfig shortStale = OpportunityTestFixtures.config(
                ItemFilterPolicy.allowAll(), OpportunityTestFixtures.thresholds("20", "30"),
                new OpportunityThresholds(70, 8, com.example.donutflipscanner.market.risk.MarketRiskLevel.MODERATE),
                "config-v1", "filters-v1", Duration.ofMinutes(1)
        );
        OpportunityEvaluationRequest request = OpportunityTestFixtures.request("50");
        detector.evaluate(request, shortStale, NOW);

        assertTrue(detector.evaluate(request, shortStale, NOW.plusSeconds(1)).duplicateUnchangedListing());
        OpportunityDetectionResult stale = detector.evaluate(request, shortStale, NOW.plus(Duration.ofMinutes(5)));
        assertTrue(stale.reevaluation().reasons().contains(ReevaluationReason.MARKET_DATA_STALE));
    }

    @Test
    void restoredTrackingCacheHasAHardLimit() {
        OpportunityDetector detector = new OpportunityDetector();
        OpportunityEvaluation base = new OpportunityEvaluator().evaluate(
                OpportunityTestFixtures.request("50"), config, NOW
        );
        OpportunityRevision revision = OpportunityRevision.from(OpportunityTestFixtures.request("50"), config);
        for (int index = 0; index <= OpportunityDetector.MAXIMUM_TRACKED_OPPORTUNITIES; index++) {
            OpportunityEvaluation value = new OpportunityEvaluation(
                    "restored-" + index, "listing-" + index, base.itemFingerprint(), base.itemId(),
                    base.evaluationVersion(), base.evaluatedAt(), base.listingPrice(), base.itemCount(),
                    base.state(), base.accepted(), base.highPriorityAlertEligible(),
                    base.alertSuppressions(), base.explanation()
            );
            detector.restore(new OpportunityTrackingSnapshot(value, revision, Optional.empty()));
        }

        assertEquals(OpportunityDetector.MAXIMUM_TRACKED_OPPORTUNITIES, detector.trackedCount());
        assertTrue(detector.find("restored-0").isEmpty());
    }
}
