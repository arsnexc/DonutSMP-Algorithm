package com.example.donutflipscanner.automation;

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
import com.example.donutflipscanner.automation.model.TradeExecutionResult;
import com.example.donutflipscanner.automation.model.TradeExecutionState;
import com.example.donutflipscanner.automation.service.AuctionInteractionAdapter;
import com.example.donutflipscanner.automation.service.DryRunAuctionInteractionAdapter;
import com.example.donutflipscanner.automation.service.RelistPricingService;
import com.example.donutflipscanner.automation.service.TradeAutomationCoordinator;
import com.example.donutflipscanner.automation.service.TradeExecutionObserver;
import com.example.donutflipscanner.configuration.AutomationConfig;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TradeAutomationCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-08-04T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void dryRunCompletesWithoutArmingOrMinecraftInteraction() {
        AtomicInteger authorizedCalls = new AtomicInteger();
        AuctionInteractionAdapter authorized = new DelegatingAdapter() {
            @Override
            public CompletableFuture<AuctionLocateResult> locateListing(TradeExecutionRequest request) {
                authorizedCalls.incrementAndGet();
                return super.locateListing(request);
            }
        };
        TradeAutomationCoordinator coordinator = new TradeAutomationCoordinator(
                AutomationConfig::defaults, new DryRunAuctionInteractionAdapter(), authorized,
                new RelistPricingService(), TradeExecutionObserver.noOp(), CLOCK
        );

        TradeExecutionResult result = coordinator.submit(request("listing-1", AutomationMode.DRY_RUN)).join();

        assertTrue(result.successful());
        assertEquals(TradeExecutionState.COMPLETED, result.state());
        assertFalse(coordinator.snapshot().sessionArmed());
        assertEquals(0, coordinator.snapshot().purchasesThisSession());
        assertEquals(0, authorizedCalls.get());
    }

    @Test
    void realModeRequiresExactAllowlistAndSessionPhrase() {
        AutomationConfig config = enabledConfig(AutomationMode.AUTOMATIC_AUTHORIZED_SERVER);
        TradeAutomationCoordinator coordinator = coordinator(config, new DryRunAuctionInteractionAdapter());

        assertFalse(coordinator.armSession("example.test", "I have authorization"));
        assertFalse(coordinator.armSession("other.test", TradeAutomationCoordinator.ARM_CONFIRMATION));
        TradeExecutionResult disarmed = coordinator.submit(
                request("listing-2", AutomationMode.AUTOMATIC_AUTHORIZED_SERVER)
        ).join();
        assertFalse(disarmed.successful());
        assertTrue(disarmed.message().contains("allowlisted and armed"));

        assertTrue(coordinator.armSession("EXAMPLE.TEST", TradeAutomationCoordinator.ARM_CONFIRMATION));
        TradeExecutionResult completed = coordinator.submit(
                request("listing-3", AutomationMode.AUTOMATIC_AUTHORIZED_SERVER)
        ).join();
        assertTrue(completed.successful());
        assertEquals(1, coordinator.snapshot().purchasesThisSession());
    }

    @Test
    void candidateMismatchFailsBeforePurchase() {
        AtomicInteger purchases = new AtomicInteger();
        AuctionInteractionAdapter mismatchAdapter = new DelegatingAdapter() {
            @Override
            public CompletableFuture<AuctionLocateResult> locateListing(TradeExecutionRequest request) {
                AuctionListingCandidate wrongPrice = new AuctionListingCandidate(
                        request.listingKey(), request.itemFingerprint(), request.itemId(),
                        request.expectedItemCount(), request.expectedSeller(), new BigDecimal("25001")
                );
                return CompletableFuture.completedFuture(AuctionLocateResult.found(wrongPrice));
            }

            @Override
            public CompletableFuture<PurchaseResult> purchase(
                    TradeExecutionRequest request, AuctionListingCandidate candidate
            ) {
                purchases.incrementAndGet();
                return super.purchase(request, candidate);
            }
        };
        TradeAutomationCoordinator coordinator = coordinator(AutomationConfig.defaults(), mismatchAdapter);

        TradeExecutionResult result = coordinator.submit(request("listing-4", AutomationMode.DRY_RUN)).join();

        assertFalse(result.successful());
        assertTrue(result.message().contains("differs"));
        assertEquals(0, purchases.get());
    }

    @Test
    void listingIdsAreIdempotentAndEmergencyStopFailsClosed() {
        TradeAutomationCoordinator coordinator = coordinator(AutomationConfig.defaults(),
                new DryRunAuctionInteractionAdapter());
        assertTrue(coordinator.submit(request("listing-5", AutomationMode.DRY_RUN)).join().successful());

        TradeExecutionResult duplicate = coordinator.submit(request("listing-5", AutomationMode.DRY_RUN)).join();
        assertFalse(duplicate.successful());
        assertTrue(duplicate.message().contains("already been submitted"));

        coordinator.emergencyStop("test stop");
        TradeExecutionResult stopped = coordinator.submit(request("listing-6", AutomationMode.DRY_RUN)).join();
        assertFalse(stopped.successful());
        assertTrue(stopped.message().contains("Emergency stop"));
    }

    @Test
    void emergencyStopCancelsEveryAsynchronousExecutionPhase() {
        for (GateStage stage : GateStage.values()) {
            GateAdapter adapter = new GateAdapter(stage);
            TradeAutomationCoordinator coordinator = coordinator(
                    enabledConfig(AutomationMode.AUTOMATIC_AUTHORIZED_SERVER), adapter
            );
            assertTrue(coordinator.armSession(
                    "example.test", TradeAutomationCoordinator.ARM_CONFIRMATION
            ));

            CompletableFuture<TradeExecutionResult> execution = coordinator.submit(request(
                    "emergency-" + stage.name().toLowerCase(),
                    AutomationMode.AUTOMATIC_AUTHORIZED_SERVER
            ));
            adapter.reached.join();
            coordinator.emergencyStop("test emergency at " + stage);
            adapter.release.complete(null);

            assertEquals(TradeExecutionState.CANCELLED, execution.join().state(), stage.name());
            assertTrue(coordinator.snapshot().emergencyStopped(), stage.name());
        }
    }

    private static TradeAutomationCoordinator coordinator(
            AutomationConfig config, AuctionInteractionAdapter authorizedAdapter
    ) {
        return new TradeAutomationCoordinator(
                () -> config,
                authorizedAdapter,
                authorizedAdapter,
                new RelistPricingService(),
                TradeExecutionObserver.noOp(),
                CLOCK
        );
    }

    private static AutomationConfig enabledConfig(AutomationMode mode) {
        return new AutomationConfig(
                true, mode, Set.of("example.test"),
                new BigDecimal("100000"), new BigDecimal("200000"), 3,
                30, 8, new BigDecimal("1000"), new BigDecimal("10"),
                30, 0, RelistPricingStrategy.CONSERVATIVE_FAIR_VALUE,
                new BigDecimal("30"), new BigDecimal("20"),
                true, false, true, true
        );
    }

    private static TradeExecutionRequest request(String listingId, AutomationMode mode) {
        return new TradeExecutionRequest(
                "execution-" + listingId,
                "opportunity-" + listingId,
                listingId,
                "minecraft:diamond_leggings|exact",
                "minecraft:diamond_leggings",
                1,
                Optional.of("AuthorizedSeller"),
                new BigDecimal("25000"),
                new BigDecimal("66500"),
                new BigDecimal("37000"),
                new BigDecimal("149"),
                60,
                8,
                MarketRiskLevel.LOW,
                NOW.minusSeconds(2),
                new BigDecimal("25000"),
                30,
                mode
        );
    }

    private static class DelegatingAdapter implements AuctionInteractionAdapter {
        private final DryRunAuctionInteractionAdapter delegate = new DryRunAuctionInteractionAdapter();

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
            return delegate.purchase(request, candidate);
        }

        @Override
        public CompletableFuture<InventoryVerificationResult> verifyPurchase(TradeExecutionRequest request) {
            return delegate.verifyPurchase(request);
        }

        @Override
        public CompletableFuture<ListingResult> listForSale(TradeExecutionRequest request, RelistPlan relistPlan) {
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

    private enum GateStage {
        LOCATE,
        VERIFY_LISTING,
        PURCHASE,
        VERIFY_PURCHASE,
        LIST_FOR_SALE,
        VERIFY_LISTING_CREATED
    }

    private static final class GateAdapter extends DelegatingAdapter {
        private final GateStage gatedStage;
        private final CompletableFuture<Void> reached = new CompletableFuture<>();
        private final CompletableFuture<Void> release = new CompletableFuture<>();

        private GateAdapter(GateStage gatedStage) {
            this.gatedStage = gatedStage;
        }

        @Override
        public CompletableFuture<AuctionLocateResult> locateListing(TradeExecutionRequest request) {
            return gate(GateStage.LOCATE, () -> super.locateListing(request));
        }

        @Override
        public CompletableFuture<AuctionVerificationResult> verifyListing(
                TradeExecutionRequest request, AuctionListingCandidate candidate
        ) {
            return gate(GateStage.VERIFY_LISTING, () -> super.verifyListing(request, candidate));
        }

        @Override
        public CompletableFuture<PurchaseResult> purchase(
                TradeExecutionRequest request, AuctionListingCandidate candidate
        ) {
            return gate(GateStage.PURCHASE, () -> super.purchase(request, candidate));
        }

        @Override
        public CompletableFuture<InventoryVerificationResult> verifyPurchase(TradeExecutionRequest request) {
            return gate(GateStage.VERIFY_PURCHASE, () -> super.verifyPurchase(request));
        }

        @Override
        public CompletableFuture<ListingResult> listForSale(
                TradeExecutionRequest request, RelistPlan relistPlan
        ) {
            return gate(GateStage.LIST_FOR_SALE, () -> super.listForSale(request, relistPlan));
        }

        @Override
        public CompletableFuture<ListingVerificationResult> verifyListingCreated(
                TradeExecutionRequest request, RelistPlan relistPlan
        ) {
            return gate(GateStage.VERIFY_LISTING_CREATED,
                    () -> super.verifyListingCreated(request, relistPlan));
        }

        private <T> CompletableFuture<T> gate(
                GateStage stage, Supplier<CompletableFuture<T>> continuation
        ) {
            if (stage != gatedStage) {
                return continuation.get();
            }
            reached.complete(null);
            return release.thenCompose(ignored -> continuation.get());
        }
    }
}
