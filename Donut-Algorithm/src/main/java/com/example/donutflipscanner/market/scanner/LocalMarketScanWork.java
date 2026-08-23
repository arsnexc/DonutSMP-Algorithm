package com.example.donutflipscanner.market.scanner;

import com.example.donutflipscanner.api.LocalMarketDataProvider;
import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiTransactionPage;
import com.example.donutflipscanner.service.MarketDataIngestionService;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Local market scan work that generates sample data without requiring an API key.
 * Used when running in offline/local mode.
 */
public final class LocalMarketScanWork implements MarketScanWork {
    private static final int MAXIMUM_COMPLETED_TRANSACTION_PAGE = 10;
    private final LocalMarketDataProvider dataProvider;
    private final MarketDataIngestionService ingestion;
    private final int firstActiveRefreshPage;
    private final int maximumActiveRefreshPage;
    private final AtomicInteger nextActiveRefreshPage;
    private final AtomicLong completedTransactionPollSequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public LocalMarketScanWork(
            LocalMarketDataProvider dataProvider,
            MarketDataIngestionService ingestion,
            int firstActiveRefreshPage,
            int maximumActiveRefreshPage
    ) {
        this.dataProvider = Objects.requireNonNull(dataProvider, "dataProvider");
        this.ingestion = Objects.requireNonNull(ingestion, "ingestion");
        if (firstActiveRefreshPage < 1 || maximumActiveRefreshPage < firstActiveRefreshPage) {
            throw new IllegalArgumentException("active listing page range is invalid");
        }
        this.firstActiveRefreshPage = firstActiveRefreshPage;
        this.maximumActiveRefreshPage = maximumActiveRefreshPage;
        nextActiveRefreshPage = new AtomicInteger(firstActiveRefreshPage);
    }

    @Override
    public CompletableFuture<ScanBatchResult> pollRecentlyListed() {
        if (closed.get()) {
            return closedFuture();
        }
        return dataProvider.fetchRecentlyListedAuctions(1).thenCompose(page ->
                ingestion.ingestListings("recent-listings:1", page, Instant.now())
        );
    }

    @Override
    public CompletableFuture<ScanBatchResult> pollCompletedTransactions() {
        if (closed.get()) {
            return closedFuture();
        }
        long sequence = completedTransactionPollSequence.getAndIncrement();
        int pageNumber = completedTransactionPage(sequence);
        return dataProvider.fetchCompletedTransactions(pageNumber).thenCompose(page ->
                ingestion.ingestTransactions("completed-transactions:" + pageNumber, page, Instant.now())
        );
    }

    @Override
    public CompletableFuture<ScanBatchResult> refreshActiveListings() {
        if (closed.get()) {
            return closedFuture();
        }
        int pageNumber = advanceActivePage();
        return dataProvider.fetchAuctionListings(pageNumber).thenCompose(page ->
                ingestion.ingestListings("active-listings:" + pageNumber, page, Instant.now())
        );
    }

    @Override
    public CompletableFuture<ScanBatchResult> recalculateStatistics(Set<String> changedFingerprints) {
        return closed.get() ? closedFuture() : ingestion.recalculateStatistics(changedFingerprints);
    }

    @Override
    public CompletableFuture<ScanBatchResult> runRetentionCleanup() {
        return closed.get() ? closedFuture() : ingestion.runRetentionCleanup();
    }

    @Override
    public CompletableFuture<Void> flushPendingWrites() {
        return closed.get() ? CompletableFuture.completedFuture(null) : ingestion.flushPendingWrites();
    }

    @Override
    public CompletableFuture<Void> saveConfiguration() {
        return closed.get() ? CompletableFuture.completedFuture(null) : ingestion.saveConfiguration();
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        dataProvider.close();
        ingestion.close();
    }

    private int advanceActivePage() {
        return nextActiveRefreshPage.getAndUpdate(current ->
                current >= maximumActiveRefreshPage ? firstActiveRefreshPage : current + 1
        );
    }

    static int completedTransactionPage(long sequence) {
        return (sequence & 1L) == 0L
                ? 1
                : 2 + (int) Math.floorMod(sequence / 2L, MAXIMUM_COMPLETED_TRANSACTION_PAGE - 1L);
    }

    private static <T> CompletableFuture<T> closedFuture() {
        return CompletableFuture.failedFuture(new IllegalStateException("Market scan work is closed"));
    }
}
