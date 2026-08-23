package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.database.entity.SaleEntity;
import com.example.donutflipscanner.market.confidence.ConfidenceConfig;
import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.ItemMatchQuality;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import com.example.donutflipscanner.market.profit.CapitalLimits;
import com.example.donutflipscanner.market.profit.ProfitEvaluationConfig;
import com.example.donutflipscanner.market.profit.ProfitThresholds;
import com.example.donutflipscanner.market.profit.TradingCostConfig;
import com.example.donutflipscanner.market.risk.ManipulationRiskConfig;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import com.example.donutflipscanner.market.statistics.MarketLookbackPeriod;
import com.example.donutflipscanner.market.statistics.MarketStatisticsCalculator;
import com.example.donutflipscanner.market.statistics.MarketStatisticsConfig;
import com.example.donutflipscanner.market.statistics.RecencyWeightPolicy;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;
import com.example.donutflipscanner.market.value.FairValueConfig;
import com.example.donutflipscanner.market.value.FairValueMarketContext;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

final class OpportunityTestFixtures {
    static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    static final NormalizedItem ITEM = new ItemNormalizer().normalize(
            ItemDescriptor.simple("minecraft:netherite_ingot", 1)
    );
    static final NormalizedItem CUSTOM_ITEM = new ItemNormalizer().normalize(new ItemDescriptor(
            Optional.of("minecraft:netherite_ingot"), OptionalInt.of(1), Optional.of("Risky Ingot"),
            List.of(), List.of(), Optional.empty(), List.of(), List.of()
    ));

    private OpportunityTestFixtures() {
    }

    static OpportunityEvaluationConfig config(ItemFilterPolicy filters, ProfitThresholds profitThresholds) {
        return config(filters, profitThresholds, new OpportunityThresholds(70, 8, MarketRiskLevel.MODERATE),
                "config-v1", "filters-v1", Duration.ofMinutes(1));
    }

    static OpportunityEvaluationConfig config(
            ItemFilterPolicy filters,
            ProfitThresholds profitThresholds,
            OpportunityThresholds thresholds,
            String configVersion,
            String filterVersion,
            Duration cooldown
    ) {
        return new OpportunityEvaluationConfig(
                "opportunity-v1", configVersion, filterVersion, filters, SupportedItemPolicy.safeDefaults(),
                FairValueConfig.defaults(),
                new ProfitEvaluationConfig(
                        TradingCostConfig.defaults(), profitThresholds, CapitalLimits.unlimited()
                ),
                ConfidenceConfig.defaults(), ManipulationRiskConfig.defaults(), thresholds,
                cooldown, Duration.ofMinutes(5)
        );
    }

    static ProfitThresholds thresholds(String netProfit, String roi) {
        return new ProfitThresholds(
                BigDecimal.ZERO, new BigDecimal(netProfit), new BigDecimal(roi), Optional.empty()
        );
    }

    static OpportunityEvaluationRequest request(String listingPrice) {
        return request(ITEM, stableMarket(ITEM), listing("listing-one", ITEM, listingPrice, ListingState.ACTIVE),
                Optional.empty(), 1, false);
    }

    static OpportunityEvaluationRequest request(
            NormalizedItem item,
            FairValueMarketContext market,
            ListingEntity listing,
            Optional<ItemEvaluationProfile> profile,
            long salesVersion,
            boolean restarted
    ) {
        return new OpportunityEvaluationRequest(
                listing, item, market, profile, BigDecimal.ZERO, Duration.ZERO,
                Optional.of(Duration.ofDays(30)), salesVersion, restarted
        );
    }

    static OpportunityEvaluationRequest withSalesVersion(OpportunityEvaluationRequest request, long version) {
        return new OpportunityEvaluationRequest(
                request.listing(), request.item(), request.market(), request.itemProfile(),
                request.currentOpenExposure(), request.marketSnapshotAge(), request.variantKnownAge(),
                version, request.scannerRestarted()
        );
    }

    static ListingEntity listing(String key, NormalizedItem item, String price, ListingState state) {
        return new ListingEntity(
                key, Optional.of("remote-" + key), Optional.of("seller-uuid"), Optional.of("Seller"),
                item.fingerprint().sha256(), item.itemId(), item.stackCount().orElse(1),
                new BigDecimal(price), Optional.empty(), NOW.minusSeconds(60), NOW,
                Optional.of(NOW.minusSeconds(60)), Optional.empty(), state, 0, Optional.empty()
        );
    }

    static FairValueMarketContext stableMarket(NormalizedItem item) {
        return market(item, false, List.of("104", "105"), false);
    }

    static FairValueMarketContext severeRiskMarket(NormalizedItem item) {
        return new FairValueMarketContext(
                statistics(item, nearPrices(5, 99), List.of(), MarketLookbackPeriod.THREE_DAYS, true),
                statistics(item, nearPrices(5, 180), List.of(), MarketLookbackPeriod.SIX_HOURS, true),
                statistics(item, nearPrices(30, 99), List.of(), MarketLookbackPeriod.THIRTY_DAYS, true)
        );
    }

    static NormalizedItem withMatchType(NormalizedItem source, ItemMatchType matchType) {
        return new NormalizedItem(
                source.itemId(), source.stackCount(), source.customName(), source.lore(), source.enchantments(),
                source.armorTrim(), source.contents(), source.contentsTruncated(), source.unrecognizedFields(),
                ItemMatchQuality.of(matchType, List.of()), source.fingerprint()
        );
    }

    private static FairValueMarketContext market(
            NormalizedItem item,
            boolean oneSeller,
            List<String> asks,
            boolean risingRecent
    ) {
        return new FairValueMarketContext(
                statistics(item, nearPrices(40, 99), asks, MarketLookbackPeriod.THREE_DAYS, oneSeller),
                statistics(item, nearPrices(10, risingRecent ? 180 : 99), List.of(),
                        MarketLookbackPeriod.SIX_HOURS, oneSeller),
                statistics(item, nearPrices(30, 99), List.of(),
                        MarketLookbackPeriod.THIRTY_DAYS, oneSeller)
        );
    }

    private static ItemMarketStatistics statistics(
            NormalizedItem item,
            List<String> prices,
            List<String> asks,
            MarketLookbackPeriod lookback,
            boolean oneSeller
    ) {
        List<SaleEntity> sales = new ArrayList<>();
        for (int index = 0; index < prices.size(); index++) {
            sales.add(new SaleEntity(
                    "sale-" + lookback + "-" + index, Optional.empty(),
                    Optional.of(oneSeller ? "seller-one" : "seller-" + index), Optional.empty(),
                    Optional.of("buyer-" + index), Optional.empty(), item.fingerprint().sha256(),
                    item.itemId(), 1, new BigDecimal(prices.get(index)), Optional.empty(),
                    NOW.minus(Duration.ofMinutes(index + 1L)), NOW, Optional.empty()
            ));
        }
        List<ListingEntity> listings = new ArrayList<>();
        for (int index = 0; index < asks.size(); index++) {
            listings.add(listing("ask-" + lookback + "-" + index, item, asks.get(index), ListingState.ACTIVE));
        }
        return new MarketStatisticsCalculator().calculate(
                item, sales, listings,
                new MarketStatisticsConfig(
                        lookback, 1, 100, new BigDecimal("3.5"), new BigDecimal("1.5"),
                        10_000, 10_000, Duration.ofHours(6), RecencyWeightPolicy.defaults()
                ),
                NOW
        );
    }

    private static List<String> nearPrices(int count, int center) {
        List<String> result = new ArrayList<>();
        int[] offsets = {-1, 0, 1};
        for (int index = 0; index < count; index++) {
            result.add(Integer.toString(center + offsets[index % offsets.length]));
        }
        return result;
    }
}
