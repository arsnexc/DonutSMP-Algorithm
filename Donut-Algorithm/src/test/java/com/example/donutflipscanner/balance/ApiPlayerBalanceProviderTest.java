package com.example.donutflipscanner.balance;

import com.example.donutflipscanner.api.ApiClientConfig;
import com.example.donutflipscanner.api.ApiHttpResponse;
import com.example.donutflipscanner.api.ApiRequestScheduler;
import com.example.donutflipscanner.api.ApiRetryPolicy;
import com.example.donutflipscanner.api.DonutApiClient;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ApiPlayerBalanceProviderTest {
    @Test
    void automaticallyRefreshesOnceAndReusesTheCachedSnapshot() {
        AtomicInteger requests = new AtomicInteger();
        ApiClientConfig config = new ApiClientConfig(
                Duration.ofSeconds(1), Duration.ofSeconds(1), "Donut-Algorithm/test",
                25, 1_000_000, new ApiRetryPolicy(1, List.of(), Duration.ZERO, 0.0)
        );
        try (DonutApiClient client = new DonutApiClient(
                () -> java.util.Optional.of("test-key".toCharArray()), config,
                request -> {
                    requests.incrementAndGet();
                    return CompletableFuture.completedFuture(new ApiHttpResponse(
                            200, Map.of(),
                            "{\"status\":200,\"result\":{\"money\":\"$2.8M\"}}",
                            Duration.ofMillis(2)
                    ));
                }, new ApiRequestScheduler()
        )) {
            ApiPlayerBalanceProvider provider = new ApiPlayerBalanceProvider(
                    client, () -> "Example_Player", Duration.ofSeconds(30),
                    Clock.fixed(Instant.parse("2026-08-04T12:00:00Z"), ZoneOffset.UTC)
            );

            assertEquals("2800000", provider.snapshot().amount().orElseThrow().toPlainString());
            assertEquals("2800000", provider.snapshot().amount().orElseThrow().toPlainString());
            assertEquals(1, requests.get());
        }
    }
}
