package com.example.donutflipscanner.client;

import com.example.donutflipscanner.api.ApiConnectionSnapshot;
import com.example.donutflipscanner.api.ApiConnectionState;
import com.example.donutflipscanner.data.ApiConnectionStatus;
import com.example.donutflipscanner.data.ClientUiDataSourceRouter;
import com.example.donutflipscanner.data.ClientUiDataSources;
import com.example.donutflipscanner.data.FlipOpportunity;
import com.example.donutflipscanner.data.ItemFilterSnapshot;
import com.example.donutflipscanner.data.MarketStatistics;
import com.example.donutflipscanner.data.mock.MockApiConnectionStatusProvider;
import com.example.donutflipscanner.data.mock.MockItemFilterController;
import com.example.donutflipscanner.data.mock.MockItemSearchProvider;
import com.example.donutflipscanner.data.mock.MockMarketStatisticsProvider;
import com.example.donutflipscanner.data.mock.MockOpportunityHistoryProvider;
import com.example.donutflipscanner.data.mock.MockOpportunityProvider;
import com.example.donutflipscanner.data.mock.MockScannerStatusProvider;
import com.example.donutflipscanner.data.provider.DatabaseOpportunityHistoryProvider;
import com.example.donutflipscanner.data.provider.LiveApiConnectionStatusProvider;
import com.example.donutflipscanner.data.provider.LiveItemFilterController;
import com.example.donutflipscanner.data.provider.LiveMarketStatisticsProvider;
import com.example.donutflipscanner.data.provider.LiveNotificationSettingsController;
import com.example.donutflipscanner.data.provider.MarketOpportunityProvider;
import com.example.donutflipscanner.database.DatabaseManager;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.FingerprintRepository;
import com.example.donutflipscanner.database.OpportunityRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ItemFingerprintEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.market.opportunity.ItemFilterMode;
import com.example.donutflipscanner.market.opportunity.ItemFilterPolicy;
import com.example.donutflipscanner.market.opportunity.OpportunityState;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;
import com.example.donutflipscanner.diagnostics.PerformanceMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClientProviderIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void liveProvidersExposeSnapshotsAndSafeManualActions() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("client-providers.db"));
        try {
            ListingRepository listings = new ListingRepository(database);
            OpportunityRepository opportunities = new OpportunityRepository(database);
            new FingerprintRepository(database).insertIfAbsent(new ItemFingerprintEntity(
                    "b".repeat(64), "minecraft:netherite_ingot", "COMMODITY", "{}", NOW
            )).join();
            listings.upsertBatch(List.of(listing())).join();
            opportunities.upsert(opportunity()).join();
            LiveMarketSnapshotService snapshots = new LiveMarketSnapshotService(
                    listings, new SaleRepository(database), opportunities, Optional.empty(),
                    new PerformanceMetrics(), Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC),
                    Duration.ofSeconds(15)
            );
            snapshots.refresh().join();
            MarketOpportunityProvider cards = new MarketOpportunityProvider(
                    snapshots, Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC)
            );
            DatabaseOpportunityHistoryProvider history = new DatabaseOpportunityHistoryProvider(snapshots);
            LiveMarketStatisticsProvider statistics = new LiveMarketStatisticsProvider(snapshots);

            FlipOpportunity card = cards.getOpportunities().getFirst();
            assertEquals("Netherite Ingot", card.itemName());
            assertEquals(20_800_000L, card.fairValue());
            assertEquals(87.0D, card.confidencePercent());
            assertTrue(card.listingAge().startsWith("VERIFIED "));
            assertTrue(new MarketOpportunityProvider(
                    snapshots, Clock.fixed(NOW.plusSeconds(16), ZoneOffset.UTC)
            ).getOpportunities().isEmpty());
            assertTrue(cards.reviewManually(card.opportunityId()).join().message().contains("manually"));
            assertTrue(cards.dismiss(card.opportunityId()).join());
            assertTrue(cards.getOpportunities().isEmpty());
            assertEquals(OpportunityState.DISMISSED.name(), history.getHistory().getFirst().state());
            MarketStatistics market = statistics.getMarketStatistics();
            assertEquals(1, market.databaseOpportunityCount());
        } finally {
            database.close();
        }
    }

    @Test
    void filterChangesRemainExclusiveAndTriggerAsyncReevaluation() {
        AtomicInteger reevaluations = new AtomicInteger();
        LiveItemFilterController filters = new LiveItemFilterController(
                ItemFilterPolicy.allowAll(),
                policy -> {
                    reevaluations.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }
        );

        filters.setBlacklisted("minecraft:diamond", true).join();
        filters.setWhitelisted("minecraft:diamond", true).join();
        filters.setMode(ItemFilterMode.WHITELIST_ONLY).join();
        ItemFilterSnapshot snapshot = filters.getItemFilters();

        assertTrue(snapshot.whitelistedItems().contains("minecraft:diamond"));
        assertFalse(snapshot.blacklistedItems().contains("minecraft:diamond"));
        assertEquals(ItemFilterMode.WHITELIST_ONLY, snapshot.mode());
        assertEquals(3, reevaluations.get());
        assertFalse(snapshot.reevaluationPending());
    }

    @Test
    void apiRateLimitAndErrorSnapshotsProduceNonintrusiveStatusWarnings() {
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        ApiConnectionSnapshot rateLimited = new ApiConnectionSnapshot(
                ApiConnectionState.RATE_LIMITED, Optional.of(NOW.minusSeconds(3)), Optional.of(NOW),
                Optional.of("API request limit reached"), Duration.ofSeconds(28), 12, Duration.ofMillis(145)
        );
        LiveApiConnectionStatusProvider provider = new LiveApiConnectionStatusProvider(() -> rateLimited, clock);

        ApiConnectionStatus status = provider.getApiConnectionStatus();

        assertEquals("Rate limited", status.displayName());
        assertEquals(28, status.cooldownSeconds());
        assertEquals(12, status.requestsInCurrentWindow());
        assertEquals(145, status.averageLatencyMillis());
        assertTrue(status.warning().isPresent());
    }

    @Test
    void routerSwitchesExistingGuiBundleFromMockToLive() {
        ClientUiDataSources mock = ClientUiDataSources.createMock();
        ClientUiDataSourceRouter router = new ClientUiDataSourceRouter(mock);
        MockOpportunityProvider liveOpportunities = new MockOpportunityProvider();
        ClientUiDataSources live = new ClientUiDataSources(
                liveOpportunities,
                new MockScannerStatusProvider(),
                new MockMarketStatisticsProvider(liveOpportunities),
                new MockOpportunityHistoryProvider(),
                new MockItemSearchProvider(),
                () -> new ApiConnectionStatus("Connected", "now", true),
                new MockItemFilterController()
        );

        assertEquals("Mock Data", router.dataSources().apiConnectionStatus()
                .getApiConnectionStatus().displayName());
        router.select(live);
        assertEquals("Connected", router.dataSources().apiConnectionStatus()
                .getApiConnectionStatus().displayName());
        router.dataSources().notificationSettings().setNotificationsEnabled(false);
        assertFalse(live.notificationSettings().getNotificationSettings().enabled());
    }

    @Test
    void liveNotificationSettingUpdatesImmediatelyAndNotifiesPersistence() {
        AtomicInteger changes = new AtomicInteger();
        LiveNotificationSettingsController notifications = new LiveNotificationSettingsController(
                true, false, ignored -> changes.incrementAndGet()
        );

        notifications.setNotificationsEnabled(false);

        assertFalse(notifications.getNotificationSettings().enabled());
        assertFalse(notifications.getNotificationSettings().animationsEnabled());
        assertEquals(1, changes.get());
        notifications.setNotificationsEnabled(false);
        assertEquals(1, changes.get());
    }

    private static ListingEntity listing() {
        return new ListingEntity(
                "listing", Optional.empty(), Optional.of("seller-uuid"), Optional.of("Seller"),
                "b".repeat(64), "minecraft:netherite_ingot", 4, new BigDecimal("14200000"),
                Optional.of(new BigDecimal("3550000")), NOW.minusSeconds(8), NOW,
                Optional.of(NOW.minusSeconds(8)), Optional.empty(), ListingState.ACTIVE, 0, Optional.empty()
        );
    }

    private static OpportunityEntity opportunity() {
        return new OpportunityEntity(
                "opportunity", "listing", "b".repeat(64), NOW, new BigDecimal("14200000"),
                new BigDecimal("20800000"), new BigDecimal("6600000"), new BigDecimal("46"),
                new BigDecimal("87"), OpportunityState.NEW.name(), Optional.empty(),
                Optional.of("Completed sales support the conservative estimate."), "opportunity-v1"
        );
    }
}
