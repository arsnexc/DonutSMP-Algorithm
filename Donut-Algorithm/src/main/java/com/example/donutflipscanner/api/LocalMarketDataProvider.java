package com.example.donutflipscanner.api;

import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiAuctionListing;
import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiCompletedTransaction;
import com.example.donutflipscanner.api.model.ApiItemData;
import com.example.donutflipscanner.api.model.ApiPaginationMetadata;
import com.example.donutflipscanner.api.model.ApiPlayerStats;
import com.example.donutflipscanner.api.model.ApiSeller;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Provides market data locally without requiring an API key.
 * Generates realistic sample data for testing and offline use.
 */
public final class LocalMarketDataProvider implements AutoCloseable {
    private static final List<String> SAMPLE_ITEMS = List.of(
            "Diamond", "Netherite Ingot", "Elytra", "Totem of Undying",
            "Enchanted Golden Apple", "Beacon", "Nether Star", "Dragon Egg",
            "Sponge", "Cobblestone", "Oak Log", "Iron Ingot", "Gold Ingot",
            "Emerald", "Lapis Lazuli", "Redstone Dust", "Coal", "Diamond Block",
            "Netherite Block", "Shulker Box"
    );

    private static final List<String> SAMPLE_SELLERS = List.of(
            "Player123", "MarketKing", "DiamondHunter", "TradeMaster",
            "ItemFlipper", "AuctionPro", "SMP_Trader", "BlockTrader"
    );

    private static final List<String> SAMPLE_LORE = List.of(
            "Rare item", "Limited edition", "High demand", "Good condition",
            "First edition", "Collector's item", "Premium quality"
    );

    private final Random random = ThreadLocalRandom.current();

    /**
     * Generates a page of auction listings with sample data.
     */
    public CompletableFuture<ApiAuctionPage> fetchAuctionListings(int page) {
        return CompletableFuture.completedFuture(generateAuctionPage(page));
    }

    /**
     * Generates a page of recently listed auctions with sample data.
     */
    public CompletableFuture<ApiAuctionPage> fetchRecentlyListedAuctions(int page) {
        return CompletableFuture.completedFuture(generateAuctionPage(page));
    }

    /**
     * Generates a page of completed transactions with sample data.
     */
    public CompletableFuture<ApiTransactionPage> fetchCompletedTransactions(int page) {
        return CompletableFuture.completedFuture(generateTransactionPage(page));
    }

    /**
     * Generates sample player stats.
     */
    public CompletableFuture<ApiPlayerStats> fetchPlayerStats(String username) {
        BigDecimal balance = BigDecimal.valueOf(1000000L + random.nextLong(9000000L));
        return CompletableFuture.completedFuture(new ApiPlayerStats(200, balance));
    }

    private ApiAuctionPage generateAuctionPage(int page) {
        int itemCount = 5 + random.nextInt(15);
        List<ApiAuctionListing> listings = new ArrayList<>(itemCount);

        for (int i = 0; i < itemCount; i++) {
            listings.add(generateAuctionListing());
        }

        return new ApiAuctionPage(200, listings, ApiPaginationMetadata.listings(page, itemCount));
    }

    private ApiTransactionPage generateTransactionPage(int page) {
        int transactionCount = 10 + random.nextInt(20);
        List<ApiCompletedTransaction> transactions = new ArrayList<>(transactionCount);

        for (int i = 0; i < transactionCount; i++) {
            transactions.add(generateCompletedTransaction());
        }

        return new ApiTransactionPage(200, transactions, ApiPaginationMetadata.transactions(page, transactionCount));
    }

    private ApiAuctionListing generateAuctionListing() {
        String itemName = SAMPLE_ITEMS.get(random.nextInt(SAMPLE_ITEMS.size()));
        int count = 1 + random.nextInt(64);
        BigDecimal price = BigDecimal.valueOf(100L + random.nextLong(999900L));
        long timeLeft = 3600L + random.nextLong(82800L);

        ApiAuctionItem auctionItem = new ApiAuctionItem(
                Optional.of(itemName.toLowerCase().replace(" ", "_")),
                OptionalInt.of(count),
                Optional.of(itemName),
                List.of(SAMPLE_LORE.get(random.nextInt(SAMPLE_LORE.size()))),
                Optional.of(generateItemData()),
                List.of()
        );

        ApiSeller seller = new ApiSeller(
                Optional.of(SAMPLE_SELLERS.get(random.nextInt(SAMPLE_SELLERS.size()))),
                Optional.of(generateUuid())
        );

        return new ApiAuctionListing(
                Optional.of(auctionItem),
                Optional.of(price),
                Optional.of(seller),
                OptionalLong.of(timeLeft)
        );
    }

    private ApiCompletedTransaction generateCompletedTransaction() {
        String itemName = SAMPLE_ITEMS.get(random.nextInt(SAMPLE_ITEMS.size()));
        int count = 1 + random.nextInt(64);
        BigDecimal price = BigDecimal.valueOf(100L + random.nextLong(999900L));
        long soldAt = Instant.now().minusSeconds(random.nextLong(86400L)).toEpochMilli();

        ApiAuctionItem auctionItem = new ApiAuctionItem(
                Optional.of(itemName.toLowerCase().replace(" ", "_")),
                OptionalInt.of(count),
                Optional.of(itemName),
                List.of(SAMPLE_LORE.get(random.nextInt(SAMPLE_LORE.size()))),
                Optional.of(generateItemData()),
                List.of()
        );

        ApiSeller seller = new ApiSeller(
                Optional.of(SAMPLE_SELLERS.get(random.nextInt(SAMPLE_SELLERS.size()))),
                Optional.of(generateUuid())
        );

        return new ApiCompletedTransaction(
                Optional.of(auctionItem),
                Optional.of(price),
                Optional.of(seller),
                Optional.of(BigInteger.valueOf(soldAt))
        );
    }

    private ApiItemData generateItemData() {
        return new ApiItemData(
                List.of(),
                Optional.empty()
        );
    }

    private String generateUuid() {
        return String.format("%08x-%04x-%04x-%04x-%012x",
                random.nextInt(),
                random.nextInt(0x10000),
                random.nextInt(0x10000),
                random.nextInt(0x10000),
                random.nextLong(0x1000000000000L)
        );
    }

    @Override
    public void close() {
        // No resources to close for local provider
    }
}
