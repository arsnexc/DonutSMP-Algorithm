package com.example.donutflipscanner.service;

import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiAuctionListing;
import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiCompletedTransaction;
import com.example.donutflipscanner.api.model.ApiPaginationMetadata;
import com.example.donutflipscanner.api.model.ApiSeller;
import com.example.donutflipscanner.api.model.ApiTransactionPage;
import com.example.donutflipscanner.database.DatabaseManager;
import com.example.donutflipscanner.database.FingerprintRepository;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.market.scanner.ScanBatchResult;
import com.example.donutflipscanner.diagnostics.PerformanceMetrics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RepositoryMarketDataIngestionServiceTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void normalizesAndDeduplicatesWholeListingPage() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("listing-page.db"));
        RepositoryMarketDataIngestionService service = service(database, new AtomicBoolean());
        try {
            ApiAuctionListing valid = new ApiAuctionListing(
                    Optional.of(item()), Optional.of(new BigDecimal("5000000")),
                    Optional.of(seller()), java.util.OptionalLong.empty()
            );
            ApiAuctionListing invalid = new ApiAuctionListing(
                    Optional.of(item()), Optional.of(new BigDecimal("5000000")),
                    Optional.empty(), java.util.OptionalLong.empty()
            );
            ApiAuctionPage page = new ApiAuctionPage(
                    200, List.of(valid, invalid), ApiPaginationMetadata.listings(1, 2)
            );
            Instant now = Instant.parse("2026-08-03T12:00:00Z");

            ScanBatchResult first = service.ingestListings("recent:1", page, now).join();
            new ListingRepository(database).markState(
                    first.lastProcessedKey().orElseThrow(), ListingState.INACTIVE_UNKNOWN
            ).join();
            ScanBatchResult second = service.ingestListings("recent:1", page, now.plusSeconds(1)).join();

            assertEquals(1, first.changedRecords());
            assertEquals(1, first.invalidRecords());
            assertEquals(0, second.changedRecords());
            assertEquals(1, second.duplicateRecords());
            assertEquals(1, new ListingRepository(database).count().join());
            assertEquals(1, new FingerprintRepository(database).count().join());
            assertEquals(first.pageHash(), second.pageHash());
            assertEquals(now.plusSeconds(1), new ListingRepository(database)
                    .find(first.lastProcessedKey().orElseThrow()).join().orElseThrow().lastSeenAt());
            assertEquals(ListingState.ACTIVE, new ListingRepository(database)
                    .find(first.lastProcessedKey().orElseThrow()).join().orElseThrow().state());
        } finally {
            service.close();
        }
    }

    @Test
    void productionIngestionClassifiesOrdinaryVanillaItemsAsCommodities() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("ordinary-items.db"));
        RepositoryMarketDataIngestionService service = service(database, new AtomicBoolean());
        try {
            ApiAuctionItem ordinaryItem = new ApiAuctionItem(
                    Optional.of("minecraft:oak_log"), OptionalInt.of(32), Optional.empty(),
                    List.of(), Optional.empty(), List.of()
            );
            ApiAuctionPage page = new ApiAuctionPage(
                    200,
                    List.of(new ApiAuctionListing(
                            Optional.of(ordinaryItem), Optional.of(new BigDecimal("64000")),
                            Optional.of(seller()), java.util.OptionalLong.empty()
                    )),
                    ApiPaginationMetadata.listings(1, 1)
            );

            ScanBatchResult result = service.ingestListings("ordinary:1", page, Instant.now()).join();
            var listing = new ListingRepository(database)
                    .find(result.lastProcessedKey().orElseThrow()).join().orElseThrow();
            var fingerprint = new FingerprintRepository(database)
                    .find(listing.itemFingerprint()).join().orElseThrow();

            assertEquals("COMMODITY", fingerprint.matchType());
        } finally {
            service.close();
        }
    }

    @Test
    void insertsCompletedTransactionsOnceAndFlushesConfigurationOnShutdownHooks() {
        DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("transaction-page.db"));
        AtomicBoolean saved = new AtomicBoolean();
        AtomicInteger observedSales = new AtomicInteger();
        RepositoryMarketDataIngestionService service = new RepositoryMarketDataIngestionService(
                database,
                (fingerprints, now) -> CompletableFuture.completedFuture(fingerprints.size()),
                () -> {
                    saved.set(true);
                    return CompletableFuture.completedFuture(null);
                },
                MarketRetentionPolicy.defaults(),
                new PerformanceMetrics(),
                sales -> {
                    observedSales.addAndGet(sales.size());
                    return CompletableFuture.completedFuture(0);
                }
        );
        try {
            ApiCompletedTransaction transaction = new ApiCompletedTransaction(
                    Optional.of(item()), Optional.of(new BigDecimal("64000000")),
                    Optional.of(seller()), Optional.of(BigInteger.valueOf(1_775_390_400_000L))
            );
            ApiTransactionPage page = new ApiTransactionPage(
                    200, List.of(transaction), ApiPaginationMetadata.transactions(1, 1)
            );

            ScanBatchResult first = service.ingestTransactions("transactions:1", page, Instant.now()).join();
            ScanBatchResult second = service.ingestTransactions(
                    "transactions:1", page, Instant.now().plusSeconds(1)
            ).join();
            service.flushPendingWrites().join();
            service.saveConfiguration().join();

            assertEquals(1, first.changedRecords());
            assertEquals(0, second.changedRecords());
            assertEquals(1, second.duplicateRecords());
            assertEquals(1, new SaleRepository(database).count().join());
            assertEquals(2, observedSales.get());
            assertTrue(saved.get());
        } finally {
            service.close();
        }
    }

    private static RepositoryMarketDataIngestionService service(
            DatabaseManager database,
            AtomicBoolean saved
    ) {
        return new RepositoryMarketDataIngestionService(
                database,
                (fingerprints, now) -> CompletableFuture.completedFuture(fingerprints.size()),
                () -> {
                    saved.set(true);
                    return CompletableFuture.completedFuture(null);
                },
                MarketRetentionPolicy.defaults()
        );
    }

    private static ApiAuctionItem item() {
        return new ApiAuctionItem(
                Optional.of("minecraft:diamond"), OptionalInt.of(64), Optional.empty(),
                List.of(), Optional.empty(), List.of()
        );
    }

    private static ApiSeller seller() {
        return new ApiSeller(Optional.of("Seller"), Optional.of("seller-uuid"));
    }
}
