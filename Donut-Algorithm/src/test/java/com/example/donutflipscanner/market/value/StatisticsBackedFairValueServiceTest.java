package com.example.donutflipscanner.market.value;

import com.example.donutflipscanner.market.statistics.MarketLookbackPeriod;
import com.example.donutflipscanner.market.statistics.MarketStatisticsConfig;
import com.example.donutflipscanner.market.statistics.MarketStatisticsProvider;
import com.example.donutflipscanner.market.statistics.model.ItemMarketStatistics;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static com.example.donutflipscanner.market.value.FairValueTestFixtures.COMMODITY;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.STABLE_LONG_TERM;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.STABLE_PRIMARY;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.STABLE_RECENT;
import static com.example.donutflipscanner.market.value.FairValueTestFixtures.statistics;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StatisticsBackedFairValueServiceTest {
    @Test
    void requestsPrimaryRecentAndLongTermWindowsAsynchronously() {
        List<MarketLookbackPeriod> requested = java.util.Collections.synchronizedList(new ArrayList<>());
        MarketStatisticsProvider provider = (item, config) -> CompletableFuture.supplyAsync(() -> {
            requested.add(config.lookback());
            List<String> prices = switch (config.lookback()) {
                case THREE_DAYS -> STABLE_PRIMARY;
                case SIX_HOURS -> STABLE_RECENT;
                case THIRTY_DAYS -> STABLE_LONG_TERM;
                default -> throw new AssertionError("Unexpected lookback " + config.lookback());
            };
            return statistics(item, prices, List.of(), config.lookback(), java.time.Duration.ofMinutes(1));
        });
        StatisticsBackedFairValueService service = new StatisticsBackedFairValueService(
                provider, MarketStatisticsConfig.defaults(), FairValueConfig.defaults()
        );

        FairValueEstimate estimate = service.estimateFor(COMMODITY).join();

        assertTrue(estimate.sufficientData());
        assertEquals(3, requested.size());
        assertTrue(requested.containsAll(List.of(
                MarketLookbackPeriod.THREE_DAYS,
                MarketLookbackPeriod.SIX_HOURS,
                MarketLookbackPeriod.THIRTY_DAYS
        )));
    }
}
