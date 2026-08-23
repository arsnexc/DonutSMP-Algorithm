package com.example.donutflipscanner.market.risk;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.statistics.MarketLookbackPeriod;
import com.example.donutflipscanner.market.statistics.MarketStatisticsCalculator;
import com.example.donutflipscanner.market.statistics.MarketStatisticsConfig;
import com.example.donutflipscanner.market.statistics.RecencyWeightPolicy;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;
import com.example.donutflipscanner.market.value.FairValueMarketContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class ManipulationRiskTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    static final NormalizedItem COMMODITY = new ItemNormalizer().normalize(
            ItemDescriptor.simple("minecraft:netherite_ingot", 1)
    );

    private ManipulationRiskTestFixtures() {
    }

    static FairValueMarketContext normalContext(NormalizedItem item) {
        return new FairValueMarketContext(
                statistics(item, uniqueSales(nearPrices(20), Duration.ofMinutes(1), Duration.ofMinutes(30)),
                        MarketLookbackPeriod.THREE_DAYS, List.of("102", "104"), 8),
                statistics(item, uniqueSales(nearPrices(8), Duration.ofMinutes(1), Duration.ofMinutes(30)),
                        MarketLookbackPeriod.SIX_HOURS, List.of(), 8),
                statistics(item, uniqueSales(nearPrices(20), Duration.ofMinutes(1), Duration.ofHours(6)),
                        MarketLookbackPeriod.THIRTY_DAYS, List.of(), 8)
        );
    }

    static FairValueMarketContext context(
            NormalizedItem item,
            List<SaleSpec> primary,
            List<SaleSpec> recent,
            List<SaleSpec> longTerm,
            List<String> asks,
            int outlierMinimum
    ) {
        return new FairValueMarketContext(
                statistics(item, primary, MarketLookbackPeriod.THREE_DAYS, asks, outlierMinimum),
                statistics(item, recent, MarketLookbackPeriod.SIX_HOURS, List.of(), outlierMinimum),
                statistics(item, longTerm, MarketLookbackPeriod.THIRTY_DAYS, List.of(), outlierMinimum)
        );
    }

    static ItemMarketStatistics statistics(
            NormalizedItem item,
            List<SaleSpec> specs,
            MarketLookbackPeriod lookback,
            List<String> asks,
            int outlierMinimum
    ) {
        List<SaleEntity> sales = new ArrayList<>();
        for (int index = 0; index < specs.size(); index++) {
            SaleSpec spec = specs.get(index);
            sales.add(new SaleEntity(
                    "sale-" + lookback + "-" + index,
                    Optional.empty(),
                    Optional.of(spec.seller()),
                    Optional.empty(),
                    Optional.of(spec.buyer()),
                    Optional.empty(),
                    item.fingerprint().sha256(),
                    item.itemId(),
                    1,
                    new BigDecimal(spec.price()),
                    Optional.empty(),
                    NOW.minus(spec.age()),
                    NOW,
                    Optional.empty()
            ));
        }
        List<ListingEntity> listings = new ArrayList<>();
        for (int index = 0; index < asks.size(); index++) {
            listings.add(new ListingEntity(
                    "ask-" + index,
                    Optional.empty(),
                    Optional.of("ask-seller-" + index),
                    Optional.empty(),
                    item.fingerprint().sha256(),
                    item.itemId(),
                    1,
                    new BigDecimal(asks.get(index)),
                    Optional.empty(),
                    NOW.minusSeconds(60),
                    NOW,
                    Optional.of(NOW.minusSeconds(60)),
                    Optional.empty(),
                    ListingState.ACTIVE,
                    0,
                    Optional.empty()
            ));
        }
        MarketStatisticsConfig config = new MarketStatisticsConfig(
                lookback,
                1,
                outlierMinimum,
                new BigDecimal("3.5"),
                new BigDecimal("1.5"),
                10_000,
                10_000,
                Duration.ofHours(6),
                RecencyWeightPolicy.defaults()
        );
        return new MarketStatisticsCalculator().calculate(item, sales, listings, config, NOW);
    }

    static List<SaleSpec> uniqueSales(List<String> prices, Duration firstAge, Duration spacing) {
        List<SaleSpec> sales = new ArrayList<>();
        for (int index = 0; index < prices.size(); index++) {
            sales.add(new SaleSpec(
                    prices.get(index),
                    "seller-" + index,
                    "buyer-" + index,
                    firstAge.plus(spacing.multipliedBy(index))
            ));
        }
        return List.copyOf(sales);
    }

    static List<SaleSpec> oneSellerSales(List<String> prices) {
        List<SaleSpec> sales = new ArrayList<>();
        for (int index = 0; index < prices.size(); index++) {
            sales.add(new SaleSpec(
                    prices.get(index), "seller-one", "buyer-" + index, Duration.ofMinutes(index + 1)
            ));
        }
        return List.copyOf(sales);
    }

    static List<String> nearPrices(int count) {
        String[] prices = {"99", "100", "101"};
        List<String> values = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            values.add(prices[index % prices.length]);
        }
        return List.copyOf(values);
    }

    record SaleSpec(String price, String seller, String buyer, Duration age) {
    }
}
