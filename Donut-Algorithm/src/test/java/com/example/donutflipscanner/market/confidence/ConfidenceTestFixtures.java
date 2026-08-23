package com.example.donutflipscanner.market.confidence;

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

final class ConfidenceTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    static final NormalizedItem COMMODITY = new ItemNormalizer().normalize(
            ItemDescriptor.simple("minecraft:netherite_ingot", 1)
    );

    private ConfidenceTestFixtures() {
    }

    static FairValueMarketContext highQualityContext(NormalizedItem item, boolean oneSeller) {
        return new FairValueMarketContext(
                statistics(item, nearPrices(40), MarketLookbackPeriod.THREE_DAYS,
                        Duration.ofMinutes(1), Duration.ofMinutes(30), oneSeller, 2),
                statistics(item, nearPrices(8), MarketLookbackPeriod.SIX_HOURS,
                        Duration.ofMinutes(1), Duration.ofMinutes(30), oneSeller, 0),
                statistics(item, nearPrices(30), MarketLookbackPeriod.THIRTY_DAYS,
                        Duration.ofMinutes(1), Duration.ofHours(6), oneSeller, 0)
        );
    }

    static FairValueMarketContext context(
            NormalizedItem item,
            List<String> primaryPrices,
            List<String> recentPrices,
            List<String> longTermPrices,
            Duration primaryFirstAge,
            Duration primarySpacing,
            boolean oneSeller
    ) {
        return new FairValueMarketContext(
                statistics(item, primaryPrices, MarketLookbackPeriod.THREE_DAYS,
                        primaryFirstAge, primarySpacing, oneSeller, 0),
                statistics(item, recentPrices, MarketLookbackPeriod.SIX_HOURS,
                        Duration.ofMinutes(1), Duration.ofMinutes(30), oneSeller, 0),
                statistics(item, longTermPrices, MarketLookbackPeriod.THIRTY_DAYS,
                        Duration.ofMinutes(1), Duration.ofHours(6), oneSeller, 0)
        );
    }

    static ItemMarketStatistics statistics(
            NormalizedItem item,
            List<String> prices,
            MarketLookbackPeriod lookback,
            Duration firstAge,
            Duration spacing,
            boolean oneSeller,
            int activeAskCount
    ) {
        List<SaleEntity> sales = new ArrayList<>();
        for (int index = 0; index < prices.size(); index++) {
            sales.add(new SaleEntity(
                    "sale-" + lookback + "-" + index,
                    Optional.empty(),
                    Optional.of(oneSeller ? "seller-one" : "seller-" + index),
                    Optional.empty(),
                    Optional.of("buyer-" + index),
                    Optional.empty(),
                    item.fingerprint().sha256(),
                    item.itemId(),
                    1,
                    new BigDecimal(prices.get(index)),
                    Optional.empty(),
                    NOW.minus(firstAge.plus(spacing.multipliedBy(index))),
                    NOW,
                    Optional.empty()
            ));
        }
        List<ListingEntity> listings = new ArrayList<>();
        for (int index = 0; index < activeAskCount; index++) {
            listings.add(new ListingEntity(
                    "listing-" + index,
                    Optional.empty(),
                    Optional.of("ask-seller-" + index),
                    Optional.empty(),
                    item.fingerprint().sha256(),
                    item.itemId(),
                    1,
                    new BigDecimal("105"),
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
        return new MarketStatisticsCalculator().calculate(
                item,
                sales,
                listings,
                statisticsConfig(lookback),
                NOW
        );
    }

    static ConfidenceCalculationRequest request(NormalizedItem item, FairValueMarketContext market) {
        return new ConfidenceCalculationRequest(
                item, market, Duration.ofMinutes(1), Duration.ZERO, Optional.empty()
        );
    }

    static List<String> nearPrices(int count) {
        String[] values = {"99", "100", "101"};
        List<String> result = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            result.add(values[index % values.length]);
        }
        return List.copyOf(result);
    }

    private static MarketStatisticsConfig statisticsConfig(MarketLookbackPeriod lookback) {
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
}
