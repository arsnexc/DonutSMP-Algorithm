package com.example.donutflipscanner.automation.service;

import com.example.donutflipscanner.automation.model.AuctionListingCandidate;
import com.example.donutflipscanner.automation.model.AuctionLocateResult;
import com.example.donutflipscanner.automation.model.AuctionVerificationResult;
import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.InventoryVerificationResult;
import com.example.donutflipscanner.automation.model.ListingResult;
import com.example.donutflipscanner.automation.model.ListingVerificationResult;
import com.example.donutflipscanner.automation.model.PurchaseResult;
import com.example.donutflipscanner.automation.model.RelistPlan;
import com.example.donutflipscanner.automation.model.RelistPricingStrategy;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.market.item.ItemFingerprintFactory;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import com.example.donutflipscanner.provider.LiveMarketSnapshot;
import com.example.donutflipscanner.provider.MarketOpportunitySnapshot;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AutomaticOpportunityDispatcherTest {
    private static final Instant NOW = Instant.parse("2026-08-08T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final String METADATA = "{}";
    private static final String FINGERPRINT = new ItemFingerprintFactory()
            .fromCanonicalMetadata(METADATA).sha256();

    @Test
    void publishesOnceAndSuppressesDuplicateOpportunityAndListing() {
        AutomationConfig config = enabledConfig();
        CountingAdapter adapter = new CountingAdapter();
        TradeAutomationCoordinator coordinator = coordinator(config, adapter);
        coordinator.armSession("example.test", TradeAutomationCoordinator.ARM_CONFIRMATION);
        AtomicReference<LiveMarketSnapshot> latest = new AtomicReference<>(snapshot(opportunity("one", 100)));
        AutomaticOpportunityDispatcher dispatcher = new AutomaticOpportunityDispatcher(
                () -> config, coordinator, latest::get, CLOCK
        );

        dispatcher.onSnapshotPublished(latest.get());
        dispatcher.onSnapshotPublished(latest.get());

        assertEquals(1, adapter.purchaseCount);
        assertEquals(1, coordinator.snapshot().purchasesThisSession());
        assertEquals(0, dispatcher.queuedCount());
    }

    @Test
    void preservesSingleFlightAndDrainsBoundedQueue() {
        AutomationConfig config = enabledConfig();
        BlockingFirstAdapter adapter = new BlockingFirstAdapter();
        TradeAutomationCoordinator coordinator = coordinator(config, adapter);
        coordinator.armSession("example.test", TradeAutomationCoordinator.ARM_CONFIRMATION);
        LiveMarketSnapshot published = snapshot(
                opportunity("one", 100), opportunity("two", 101)
        );
        AtomicReference<LiveMarketSnapshot> latest = new AtomicReference<>(published);
        AutomaticOpportunityDispatcher dispatcher = new AutomaticOpportunityDispatcher(
                () -> config, coordinator, latest::get, CLOCK
        );

        dispatcher.onSnapshotPublished(published);
        assertEquals(1, dispatcher.queuedCount());

        TradeExecutionRequest active = adapter.firstRequest;
        adapter.firstLocate.complete(AuctionLocateResult.found(active.expectedCandidate()));

        assertEquals(2, adapter.purchaseCount);
        assertEquals(2, coordinator.snapshot().purchasesThisSession());
        assertEquals(0, dispatcher.queuedCount());
    }

    @Test
    void ignoresPublicationsUntilAutomaticModeIsArmed() {
        AutomationConfig config = enabledConfig();
        CountingAdapter adapter = new CountingAdapter();
        TradeAutomationCoordinator coordinator = coordinator(config, adapter);
        LiveMarketSnapshot published = snapshot(opportunity("one", 100));
        AtomicReference<LiveMarketSnapshot> latest = new AtomicReference<>(published);
        AutomaticOpportunityDispatcher dispatcher = new AutomaticOpportunityDispatcher(
                () -> config, coordinator, latest::get, CLOCK
        );

        dispatcher.onSnapshotPublished(published);
        assertEquals(0, adapter.purchaseCount);

        coordinator.armSession("example.test", TradeAutomationCoordinator.ARM_CONFIRMATION);
        dispatcher.onSnapshotPublished(published);
        assertEquals(1, adapter.purchaseCount);
    }

    @Test
    void revalidatesQueuedOpportunityAgainstLatestSnapshotBeforeSubmitting() {
        AutomationConfig config = enabledConfig();
        BlockingFirstAdapter adapter = new BlockingFirstAdapter();
        TradeAutomationCoordinator coordinator = coordinator(config, adapter);
        coordinator.armSession("example.test", TradeAutomationCoordinator.ARM_CONFIRMATION);
        MarketOpportunitySnapshot blocker = opportunity("blocker", 99);
        CompletableFuture<?> blockerResult = coordinator.submit(request(blocker));

        LiveMarketSnapshot published = snapshot(opportunity("queued", 100));
        AtomicReference<LiveMarketSnapshot> latest = new AtomicReference<>(published);
        AutomaticOpportunityDispatcher dispatcher = new AutomaticOpportunityDispatcher(
                () -> config, coordinator, latest::get, CLOCK
        );
        dispatcher.onSnapshotPublished(published);
        assertEquals(1, dispatcher.queuedCount());

        latest.set(snapshot());
        adapter.firstLocate.complete(AuctionLocateResult.found(adapter.firstRequest.expectedCandidate()));
        blockerResult.join();

        assertEquals(1, adapter.purchaseCount);
        assertEquals(0, dispatcher.queuedCount());
        assertEquals(1, coordinator.snapshot().purchasesThisSession());
    }

    private static TradeAutomationCoordinator coordinator(
            AutomationConfig config, AuctionInteractionAdapter adapter
    ) {
        return new TradeAutomationCoordinator(
                () -> config, adapter, adapter, new RelistPricingService(),
                TradeExecutionObserver.noOp(), CLOCK
        );
    }

    private static AutomationConfig enabledConfig() {
        return new AutomationConfig(
                true, AutomationMode.AUTOMATIC_AUTHORIZED_SERVER, Set.of("example.test"),
                new BigDecimal("100000"), new BigDecimal("500000"), 8,
                30, 8, BigDecimal.ZERO, BigDecimal.ZERO, 30, 0,
                RelistPricingStrategy.CONSERVATIVE_FAIR_VALUE, BigDecimal.ZERO, BigDecimal.ZERO,
                true, false, true, true
        );
    }

    private static LiveMarketSnapshot snapshot(MarketOpportunitySnapshot... opportunities) {
        return new LiveMarketSnapshot(
                opportunities.length, 0, opportunities.length, opportunities.length,
                BigDecimal.valueOf(1000L * opportunities.length), List.of(opportunities),
                List.of(), Optional.of(NOW), true, Optional.empty()
        );
    }

    private static MarketOpportunitySnapshot opportunity(String id, long price) {
        return new MarketOpportunitySnapshot(
                "opportunity-" + id, "listing-" + id, FINGERPRINT,
                "minecraft:diamond", 1, BigDecimal.valueOf(price),
                BigDecimal.valueOf(price + 1000), BigDecimal.valueOf(1000),
                new BigDecimal("100"), new BigDecimal("80"), 12,
                MarketRiskLevel.LOW, "NEW", NOW.minusSeconds(2), Optional.of(NOW.minusSeconds(2)),
                Optional.of("Seller" + id), NOW.minusSeconds(1), Optional.empty(),
                Optional.empty(), Optional.of(METADATA)
        );
    }

    private static TradeExecutionRequest request(MarketOpportunitySnapshot value) {
        return new TradeExecutionRequest(
                "execution-" + value.opportunityId(), value.opportunityId(), value.listingKey(),
                value.itemFingerprint(), value.itemId(), value.itemCount(), value.sellerName(),
                value.listingPrice(), value.conservativeFairValue(), value.estimatedProfit(),
                value.roiPercent(), 80, value.comparableSales(), value.riskLevel(),
                value.detectedAt(), value.listingPrice(), 30,
                AutomationMode.AUTOMATIC_AUTHORIZED_SERVER,
                Optional.of("Diamond"), value.normalizedItemMetadata()
        );
    }

    private static class CountingAdapter implements AuctionInteractionAdapter {
        private final DryRunAuctionInteractionAdapter delegate = new DryRunAuctionInteractionAdapter();
        int purchaseCount;

        @Override
        public CompletableFuture<AuctionLocateResult> locateListing(TradeExecutionRequest request) {
            return delegate.locateListing(request);
        }

        @Override
        public CompletableFuture<AuctionVerificationResult> verifyListing(
                TradeExecutionRequest request, AuctionListingCandidate candidate
        ) {
            return delegate.verifyListing(request, candidate);
        }

        @Override
        public CompletableFuture<PurchaseResult> purchase(
                TradeExecutionRequest request, AuctionListingCandidate candidate
        ) {
            purchaseCount++;
            return delegate.purchase(request, candidate);
        }

        @Override
        public CompletableFuture<InventoryVerificationResult> verifyPurchase(TradeExecutionRequest request) {
            return delegate.verifyPurchase(request);
        }

        @Override
        public CompletableFuture<ListingResult> listForSale(
                TradeExecutionRequest request, RelistPlan relistPlan
        ) {
            return delegate.listForSale(request, relistPlan);
        }

        @Override
        public CompletableFuture<ListingVerificationResult> verifyListingCreated(
                TradeExecutionRequest request, RelistPlan relistPlan
        ) {
            return delegate.verifyListingCreated(request, relistPlan);
        }

        @Override
        public CompletableFuture<Void> returnToSafeScreen() {
            return delegate.returnToSafeScreen();
        }
    }

    private static final class BlockingFirstAdapter extends CountingAdapter {
        private final CompletableFuture<AuctionLocateResult> firstLocate = new CompletableFuture<>();
        private TradeExecutionRequest firstRequest;
        private boolean first = true;

        @Override
        public CompletableFuture<AuctionLocateResult> locateListing(TradeExecutionRequest request) {
            if (first) {
                first = false;
                firstRequest = request;
                return firstLocate;
            }
            return super.locateListing(request);
        }
    }
}
