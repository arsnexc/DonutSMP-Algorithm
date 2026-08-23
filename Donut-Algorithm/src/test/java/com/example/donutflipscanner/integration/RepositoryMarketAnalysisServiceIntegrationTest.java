package com.example.donutflipscanner.integration;

import com.example.donutflipscanner.api.ApiClientConfig;
import com.example.donutflipscanner.api.ApiHttpResponse;
import com.example.donutflipscanner.api.ApiHttpTransport;
import com.example.donutflipscanner.api.ApiRequestScheduler;
import com.example.donutflipscanner.api.ApiRetryPolicy;
import com.example.donutflipscanner.api.DonutApiClient;
import com.example.donutflipscanner.database.DatabaseManager;
import com.example.donutflipscanner.database.FingerprintRepository;
import com.example.donutflipscanner.database.ListingRepository;
import com.example.donutflipscanner.database.OpportunityRepository;
import com.example.donutflipscanner.database.SaleRepository;
import com.example.donutflipscanner.market.confidence.ConfidenceConfig;
import com.example.donutflipscanner.market.opportunity.ItemFilterPolicy;
import com.example.donutflipscanner.market.opportunity.OpportunityDetector;
import com.example.donutflipscanner.market.opportunity.OpportunityEvaluationConfig;
import com.example.donutflipscanner.market.opportunity.OpportunityThresholds;
import com.example.donutflipscanner.market.opportunity.SupportedItemPolicy;
import com.example.donutflipscanner.market.profit.ProfitEvaluationConfig;
import com.example.donutflipscanner.market.risk.ManipulationRiskConfig;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import com.example.donutflipscanner.market.statistics.MarketStatisticsCalculator;
import com.example.donutflipscanner.market.statistics.RepositoryMarketStatisticsService;
import com.example.donutflipscanner.market.value.FairValueConfig;
import com.example.donutflipscanner.service.MarketRetentionPolicy;
import com.example.donutflipscanner.service.RepositoryMarketAnalysisService;
import com.example.donutflipscanner.service.RepositoryMarketDataIngestionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RepositoryMarketAnalysisServiceIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void ingestedOfficialShapesProducePersistedLiveOpportunity() {
        QueueTransport transport = new QueueTransport(List.of(
                fixture("/fixtures/pipeline-listing.json"),
                fixture("/fixtures/pipeline-transactions.json")
        ));
        ApiClientConfig apiConfig = new ApiClientConfig(
                Duration.ofSeconds(1), Duration.ofSeconds(2), "analysis-test/1", 1,
                1_048_576, new ApiRetryPolicy(1, List.of(), Duration.ZERO, 0.0D)
        );
        try (DonutApiClient api = new DonutApiClient(
                () -> java.util.Optional.of("fixture-key".toCharArray()), apiConfig, transport,
                new ApiRequestScheduler()
        )) {
            var listingPage = api.fetchRecentlyListedAuctions(1).join();
            var transactionPage = api.fetchCompletedTransactions(1).join();
            DatabaseManager database = new DatabaseManager(temporaryDirectory.resolve("analysis.db"));
            ListingRepository listings = new ListingRepository(database);
            SaleRepository sales = new SaleRepository(database);
            OpportunityRepository opportunities = new OpportunityRepository(database);
            OpportunityDetector detector = new OpportunityDetector();
            RepositoryMarketStatisticsService statistics = new RepositoryMarketStatisticsService(
                    sales, listings, new MarketStatisticsCalculator(), Clock.fixed(NOW, ZoneOffset.UTC)
            );
            RepositoryMarketAnalysisService analysis = new RepositoryMarketAnalysisService(
                    new FingerprintRepository(database), listings, opportunities, sales, statistics,
                    detector, evaluationConfig()
            );
            RepositoryMarketDataIngestionService ingestion = new RepositoryMarketDataIngestionService(
                    database, analysis, () -> CompletableFuture.completedFuture(null),
                    MarketRetentionPolicy.defaults()
            );
            try {
                var listingResult = ingestion.ingestListings("listing", listingPage, NOW).join();
                var saleResult = ingestion.ingestTransactions("sales", transactionPage, NOW).join();
                java.util.HashSet<String> changed = new java.util.HashSet<>(listingResult.changedFingerprints());
                changed.addAll(saleResult.changedFingerprints());

                assertEquals(1, analysis.refresh(changed, NOW).join());
                assertEquals(1L, opportunities.countActive().join());
                assertEquals(1, opportunities.findRecentWithListings(10).join().size());
            } finally {
                ingestion.close();
            }
        }
    }

    private static OpportunityEvaluationConfig evaluationConfig() {
        return new OpportunityEvaluationConfig(
                "live-v1", "live-config-v1", "live-filter-v1", ItemFilterPolicy.allowAll(),
                SupportedItemPolicy.safeDefaults(), FairValueConfig.defaults(),
                ProfitEvaluationConfig.defaults(), ConfidenceConfig.defaults(),
                ManipulationRiskConfig.defaults(), new OpportunityThresholds(0, 8, MarketRiskLevel.MODERATE),
                Duration.ofSeconds(30), Duration.ofMinutes(5)
        );
    }

    private static String fixture(String resource) {
        try (InputStream stream = RepositoryMarketAnalysisServiceIntegrationTest.class
                .getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing integration fixture");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read integration fixture", error);
        }
    }

    private static final class QueueTransport implements ApiHttpTransport {
        private final Queue<String> responses;

        private QueueTransport(List<String> responses) {
            this.responses = new ArrayDeque<>(responses);
        }

        @Override
        public CompletableFuture<ApiHttpResponse> sendAsync(HttpRequest request) {
            return CompletableFuture.completedFuture(
                    new ApiHttpResponse(200, Map.of(), responses.remove(), Duration.ofMillis(3))
            );
        }
    }
}
