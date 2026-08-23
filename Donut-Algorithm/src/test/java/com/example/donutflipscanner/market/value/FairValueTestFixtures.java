package com.example.donutflipscanner.market.value;

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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

final class FairValueTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    static final NormalizedItem COMMODITY = new ItemNormalizer().normalize(
            ItemDescriptor.simple("minecraft:netherite_ingot", 1)
    );
    static final List<String> STABLE_PRIMARY = List.of("95", "98", "100", "100", "102", "104", "105", "106");
    static final List<String> STABLE_RECENT = List.of("99", "100", "101");
    static final List<String> STABLE_LONG_TERM = List.of("98", "100", "102");

    private FairValueTestFixtures() {
    }

    static FairValueMarketContext stableContext(NormalizedItem item, List<String> asks) {
        return context(item, STABLE_PRIMARY, STABLE_RECENT, STABLE_LONG_TERM, asks);
    }

    static FairValueMarketContext context(
            NormalizedItem item,
            List<String> primaryPrices,
            List<String> recentPrices,
            List<String> longTermPrices,
            List<String> asks
    ) {
        return new FairValueMarketContext(
                statistics(item, primaryPrices, asks, MarketLookbackPeriod.THREE_DAYS, Duration.ofMinutes(1)),
                statistics(item, recentPrices, List.of(), MarketLookbackPeriod.SIX_HOURS, Duration.ofMinutes(1)),
                statistics(item, longTermPrices, List.of(), MarketLookbackPeriod.THIRTY_DAYS, Duration.ofMinutes(1))
        );
    }

    static ItemMarketStatistics statistics(
            NormalizedItem item,
            List<String> prices,
            List<String> asks,
            MarketLookbackPeriod lookback,
            Duration firstAge
    ) {
        List<SaleEntity> sales = new ArrayList<>();
        for (int index = 0; index < prices.size(); index++) {
            sales.add(sale(item, "sale-" + lookback + "-" + index, prices.get(index), firstAge.plusMinutes(index)));
        }
        List<ListingEntity> listings = new ArrayList<>();
        for (int index = 0; index < asks.size(); index++) {
            listings.add(listing(item, "ask-" + index, asks.get(index)));
        }
        return new MarketStatisticsCalculator().calculate(
                item, sales, listings, statisticsConfig(lookback), NOW
        );
    }

    static MarketStatisticsConfig statisticsConfig(MarketLookbackPeriod lookback) {
        return new MarketStatisticsConfig(
                lookback,
                1,
                100,
                new BigDecimal("3.5"),
                new BigDecimal("1.5"),
                10_000,
                10_000,
                Duration.ofHours(6),
                RecencyWeightPolicy.defaults()
        );
    }

    static SaleEntity sale(NormalizedItem item, String key, String price, Duration age) {
        return new SaleEntity(
                key,
                Optional.empty(),
                Optional.of("seller-" + key),
                Optional.empty(),
                Optional.of("buyer-" + key),
                Optional.empty(),
                item.fingerprint().sha256(),
                item.itemId(),
                1,
                new BigDecimal(price),
                Optional.empty(),
                NOW.minus(age),
                NOW,
                Optional.empty()
        );
    }

    private static ListingEntity listing(NormalizedItem item, String key, String price) {
        return new ListingEntity(
                key,
                Optional.empty(),
                Optional.of("seller-" + key),
                Optional.empty(),
                item.fingerprint().sha256(),
                item.itemId(),
                1,
                new BigDecimal(price),
                Optional.empty(),
                NOW.minusSeconds(60),
                NOW,
                Optional.of(NOW.minusSeconds(60)),
                Optional.empty(),
                ListingState.ACTIVE,
                0,
                Optional.empty()
        );
    }
}
