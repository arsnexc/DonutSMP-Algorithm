package com.example.donutflipscanner.market.statistics;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.NormalizedItem;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

final class MarketStatisticsTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    static final NormalizedItem COMMODITY = new ItemNormalizer().normalize(
            ItemDescriptor.simple("minecraft:netherite_ingot", 1)
    );

    private MarketStatisticsTestFixtures() {
    }

    static SaleEntity sale(String key, BigDecimal totalPrice, int count, Duration age) {
        return sale(key, totalPrice, count, age, "seller-" + key, "buyer-" + key);
    }

    static SaleEntity sale(
            String key,
            BigDecimal totalPrice,
            int count,
            Duration age,
            String seller,
            String buyer
    ) {
        return new SaleEntity(
                key,
                Optional.empty(),
                Optional.of(seller),
                Optional.empty(),
                Optional.of(buyer),
                Optional.empty(),
                COMMODITY.fingerprint().sha256(),
                COMMODITY.itemId(),
                count,
                totalPrice,
                Optional.empty(),
                NOW.minus(age),
                NOW,
                Optional.empty()
        );
    }

    static ListingEntity listing(
            String key,
            BigDecimal totalPrice,
            int count,
            String seller,
            ListingState state
    ) {
        return new ListingEntity(
                key,
                Optional.empty(),
                Optional.of(seller),
                Optional.empty(),
                COMMODITY.fingerprint().sha256(),
                COMMODITY.itemId(),
                count,
                totalPrice,
                Optional.empty(),
                NOW.minusSeconds(30),
                NOW,
                Optional.of(NOW.minusSeconds(30)),
                Optional.empty(),
                state,
                0,
                Optional.empty()
        );
    }

    static MarketStatisticsConfig config(int minimumSales, int outlierMinimum) {
        MarketStatisticsConfig defaults = MarketStatisticsConfig.defaults();
        return new MarketStatisticsConfig(
                defaults.lookback(),
                minimumSales,
                outlierMinimum,
                defaults.madModifiedZThreshold(),
                defaults.iqrMultiplier(),
                defaults.maxComparableSales(),
                defaults.maxActiveListings(),
                defaults.staleAfter(),
                defaults.recencyWeights()
        );
    }
}
