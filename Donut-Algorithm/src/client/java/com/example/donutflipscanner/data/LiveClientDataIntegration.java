package com.example.donutflipscanner.data;

import com.example.donutflipscanner.DonutFlipScannerClient;
import com.example.donutflipscanner.api.DonutApiClient;
import com.example.donutflipscanner.data.provider.LiveItemFilterController;
import com.example.donutflipscanner.data.provider.LocalApiConnectionStatusProvider;
import com.example.donutflipscanner.database.DatabaseManager;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.OpportunityRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.data.provider.LiveNotificationSettingsController;
import com.example.donutflipscanner.data.provider.AutomationSettingsController;
import com.example.donutflipscanner.data.provider.LivePersonalProfitChartProvider;
import com.example.donutflipscanner.market.opportunity.ItemFilterPolicy;
import com.example.donutflipscanner.market.opportunity.OpportunityDetector;
import com.example.donutflipscanner.market.scanner.MarketScanner;
import com.example.donutflipscanner.provider.LiveMarketSnapshotService;
import com.example.donutflipscanner.profit.PersonalProfitTracker;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Consumer;

/** Composes live providers without placing backend logic in GUI classes. */
public final class LiveClientDataIntegration {
    private LiveClientDataIntegration() {
    }

    public static LiveMarketSnapshotService attach(
            DatabaseManager database,
            DonutApiClient apiClient,
            MarketScanner scanner,
            OpportunityDetector opportunityDetector,
            ItemFilterPolicy initialFilters,
            Function<ItemFilterPolicy, CompletableFuture<Void>> applyFiltersAsync
    ) {
        Objects.requireNonNull(database, "database");
        LiveMarketSnapshotService snapshots = new LiveMarketSnapshotService(
                new ListingRepository(database), new SaleRepository(database),
                new OpportunityRepository(database), Optional.ofNullable(opportunityDetector)
        );
        LiveItemFilterController filters = new LiveItemFilterController(initialFilters, applyFiltersAsync);
        ClientUiDataSources live = ClientUiDataSources.createLive(snapshots, scanner, apiClient, filters);
        DonutFlipScannerClient.useLiveDataSources(live, scanner);
        return snapshots;
    }

    /**
     * Attaches live providers for local mode (without API key).
     * Uses LocalApiConnectionStatusProvider instead of the API client.
     */
    public static void attach(
            LiveMarketSnapshotService snapshots,
            LocalApiConnectionStatusProvider connectionStatusProvider,
            MarketScanner scanner,
            ItemFilterPolicy initialFilters,
            Function<ItemFilterPolicy, CompletableFuture<Void>> applyFiltersAsync,
            Consumer<Boolean> scannerEnabledChangeListener,
            boolean notificationsEnabled,
            boolean animationsEnabled,
            Consumer<Boolean> notificationsEnabledChangeListener,
            AutomationSettingsController automationSettings,
            PersonalProfitTracker personalProfitTracker
    ) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(scanner, "scanner");
        LiveItemFilterController filters = new LiveItemFilterController(initialFilters, applyFiltersAsync);

        // Create a minimal ClientUiDataSources for local mode
        // We use a null apiClient and handle it in the UI
        ClientUiDataSources live = createLocalDataSources(
                snapshots, scanner, filters, scannerEnabledChangeListener,
                new LiveNotificationSettingsController(
                        notificationsEnabled, animationsEnabled, notificationsEnabledChangeListener
                ),
                automationSettings,
                new LivePersonalProfitChartProvider(personalProfitTracker)
        );
        DonutFlipScannerClient.useLiveDataSources(live, scanner);
    }

    public static void attach(
            LiveMarketSnapshotService snapshots,
            DonutApiClient apiClient,
            MarketScanner scanner,
            ItemFilterPolicy initialFilters,
            Function<ItemFilterPolicy, CompletableFuture<Void>> applyFiltersAsync,
            Consumer<Boolean> scannerEnabledChangeListener,
            boolean notificationsEnabled,
            boolean animationsEnabled,
            Consumer<Boolean> notificationsEnabledChangeListener,
            AutomationSettingsController automationSettings,
            PersonalProfitTracker personalProfitTracker
    ) {
        Objects.requireNonNull(snapshots, "snapshots");
        Objects.requireNonNull(apiClient, "apiClient");
        Objects.requireNonNull(scanner, "scanner");
        LiveItemFilterController filters = new LiveItemFilterController(initialFilters, applyFiltersAsync);
        ClientUiDataSources live = ClientUiDataSources.createLive(
                snapshots, scanner, apiClient, filters, scannerEnabledChangeListener,
                new LiveNotificationSettingsController(
                        notificationsEnabled, animationsEnabled, notificationsEnabledChangeListener
                ), automationSettings, new LivePersonalProfitChartProvider(personalProfitTracker)
        );
        DonutFlipScannerClient.useLiveDataSources(live, scanner);
    }

    /**
     * Creates ClientUiDataSources for local mode without an API client.
     */
    private static ClientUiDataSources createLocalDataSources(
            LiveMarketSnapshotService snapshots,
            MarketScanner scanner,
            LiveItemFilterController itemFilters,
            Consumer<Boolean> scannerEnabledChangeListener,
            LiveNotificationSettingsController notificationSettings,
            AutomationSettingsController automationSettings,
            LivePersonalProfitChartProvider personalProfitChartProvider
    ) {
        snapshots.refresh();
        ClientUiDataSources live = ClientUiDataSources.createLocal(
                snapshots, scanner, itemFilters, scannerEnabledChangeListener,
                notificationSettings, automationSettings, personalProfitChartProvider
        );
        return live;
    }
}
