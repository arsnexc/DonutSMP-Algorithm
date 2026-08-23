package com.example.donutflipscanner.api;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DonutApiClientTest {
    @Test
    void authenticatesAndParsesAValidAuctionResponse() {
        QueueTransport transport = new QueueTransport(response(200, FixtureLoader.read("auction-valid.json")));
        char[] temporaryKeyBuffer = "secret-test-key".toCharArray();

        try (DonutApiClient client = client(() -> java.util.Optional.of(temporaryKeyBuffer), transport, oneAttemptConfig())) {
            assertEquals(1, client.fetchAuctionListings(1).join().listings().size());

            HttpRequest request = transport.requests.getFirst();
            assertEquals("Bearer secret-test-key", request.headers().firstValue("Authorization").orElseThrow());
            assertEquals("application/json", request.headers().firstValue("Accept").orElseThrow());
            assertEquals("Donut-Algorithm/test", request.headers().firstValue("User-Agent").orElseThrow());
            assertEquals("GET", request.method());
            assertTrue(request.uri().toString().endsWith("/v1/auction/list/1"));
            assertTrue(allCleared(temporaryKeyBuffer));
            assertEquals(ApiConnectionState.CONNECTED, client.connectionSnapshot().state());
        }
    }

    @Test
    void recentlyListedUsesTheDocumentedSortRequest() {
        QueueTransport transport = new QueueTransport(response(200, "{\"status\":200,\"result\":[]}"));

        try (DonutApiClient client = client(keyProvider(), transport, oneAttemptConfig())) {
            client.fetchRecentlyListedAuctions(2).join();

            HttpRequest request = transport.requests.getFirst();
            assertEquals("GET", request.method());
            assertEquals("application/json", request.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("{\"sort\":\"recently_listed\"}", readBody(request));
        }
    }

    @Test
    void parsesCompletedTransactionsFromMockHttpResponse() {
        QueueTransport transport = new QueueTransport(response(200, FixtureLoader.read("transaction-valid.json")));

        try (DonutApiClient client = client(keyProvider(), transport, oneAttemptConfig())) {
            assertEquals(1, client.fetchCompletedTransactions(3).join().transactions().size());
            assertTrue(transport.requests.getFirst().uri().toString().endsWith("/v1/auction/transactions/3"));
        }
    }

    @Test
    void fetchesAuthenticatedPlayerStatsForPassiveBalanceDisplay() {
        QueueTransport transport = new QueueTransport(response(
                200, "{\"status\":200,\"result\":{\"money\":\"$987,654\"}}"
        ));

        try (DonutApiClient client = client(keyProvider(), transport, oneAttemptConfig())) {
            assertEquals(new java.math.BigDecimal("987654"),
                    client.fetchPlayerStats("Example_Player").join().money());
            assertTrue(transport.requests.getFirst().uri().toString()
                    .endsWith("/v1/stats/Example_Player"));
        }
    }

    @Test
    void rejectsAuthenticationFailureWithoutLeakingTheKey() {
        QueueTransport transport = new QueueTransport(response(401, "{\"status\":401}"));

        try (DonutApiClient client = client(keyProvider(), transport, oneAttemptConfig())) {
            ApiAuthenticationException failure = assertFailure(
                    ApiAuthenticationException.class,
                    client.fetchAuctionListings(1)
            );
            assertFalse(failure.getMessage().contains("secret-test-key"));
            assertEquals(ApiConnectionState.AUTHENTICATION_FAILED, client.connectionSnapshot().state());
        }
    }

    @Test
    void exposesRateLimitAndRetryAfterCooldown() {
        QueueTransport transport = new QueueTransport(new ApiHttpResponse(
                429,
                Map.of("Retry-After", List.of("7")),
                "{}",
                Duration.ofMillis(5)
        ));

        try (DonutApiClient client = client(keyProvider(), transport, oneAttemptConfig())) {
            ApiRateLimitException failure = assertFailure(
                    ApiRateLimitException.class,
                    client.fetchAuctionListings(1)
            );
            assertEquals(Duration.ofSeconds(7), failure.retryAfter());
            assertEquals(ApiConnectionState.RATE_LIMITED, client.connectionSnapshot().state());
            assertTrue(client.connectionSnapshot().currentCooldown().toSeconds() >= 6);
        }
    }

    @Test
    void reportsServerErrorAfterConfiguredAttempts() {
        QueueTransport transport = new QueueTransport(response(500, "{\"status\":500}"));

        try (DonutApiClient client = client(keyProvider(), transport, oneAttemptConfig())) {
            ApiException failure = assertFailure(ApiException.class, client.fetchAuctionListings(1));
            assertEquals(500, failure.statusCode());
            assertTrue(failure.retryable());
            assertEquals(ApiConnectionState.TEMPORARY_ERROR, client.connectionSnapshot().state());
        }
    }

    @Test
    void reportsTimeoutWithoutBlocking() {
        QueueTransport transport = new QueueTransport(new java.net.http.HttpTimeoutException("fixture timeout"));

        try (DonutApiClient client = client(keyProvider(), transport, oneAttemptConfig())) {
            ApiException failure = assertFailure(ApiException.class, client.fetchCompletedTransactions(1));
            assertEquals("DonutSMP API request timed out", failure.getMessage());
            assertEquals(0, failure.statusCode());
        }
    }

    @Test
    void retriesTemporaryServerFailureWithScheduledBackoff() {
        QueueTransport transport = new QueueTransport(
                response(503, "{}"),
                response(200, "{\"status\":200,\"result\":[]}")
        );
        ApiClientConfig config = new ApiClientConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "Donut-Algorithm/test",
                25,
                1_000_000,
                new ApiRetryPolicy(2, List.of(Duration.ZERO), Duration.ZERO, 0.0)
        );

        try (DonutApiClient client = client(keyProvider(), transport, config)) {
            assertTrue(client.fetchAuctionListings(1).join().listings().isEmpty());
            assertEquals(2, transport.requests.size());
            assertEquals(ApiConnectionState.CONNECTED, client.connectionSnapshot().state());
        }
    }

    @Test
    void missingKeyDoesNotIssueARequest() {
        QueueTransport transport = new QueueTransport(response(200, "{\"status\":200,\"result\":[]}"));

        try (DonutApiClient client = client(java.util.Optional::<char[]>empty, transport, oneAttemptConfig())) {
            assertFailure(ApiAuthenticationException.class, client.fetchAuctionListings(1));
            assertTrue(transport.requests.isEmpty());
            assertEquals(ApiConnectionState.DISABLED, client.connectionSnapshot().state());
        }
    }

    private ApiCredentialsProvider keyProvider() {
        return () -> java.util.Optional.of("secret-test-key".toCharArray());
    }

    private ApiClientConfig oneAttemptConfig() {
        return new ApiClientConfig(
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                "Donut-Algorithm/test",
                25,
                1_000_000,
                new ApiRetryPolicy(1, List.of(), Duration.ZERO, 0.0)
        );
    }

    private DonutApiClient client(
            ApiCredentialsProvider keyProvider,
            QueueTransport transport,
            ApiClientConfig config
    ) {
        return new DonutApiClient(keyProvider, config, transport, new ApiRequestScheduler());
    }

    private ApiHttpResponse response(int status, String body) {
        return new ApiHttpResponse(status, Map.of(), body, Duration.ofMillis(10));
    }

    private <T extends Throwable> T assertFailure(Class<T> type, CompletableFuture<?> future) {
        CompletionException wrapper = assertThrows(CompletionException.class, future::join);
        return assertInstanceOf(type, wrapper.getCause());
    }

    private boolean allCleared(char[] value) {
        for (char character : value) {
            if (character != '\0') {
                return false;
            }
        }
        return true;
    }

    private String readBody(HttpRequest request) {
        HttpRequest.BodyPublisher publisher = request.bodyPublisher().orElseThrow();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        CompletableFuture<Void> complete = new CompletableFuture<>();
        publisher.subscribe(new Flow.Subscriber<>() {
            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.writeBytes(bytes);
            }

            @Override
            public void onError(Throwable throwable) {
                complete.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                complete.complete(null);
            }
        });
        complete.join();
        return output.toString(StandardCharsets.UTF_8);
    }

    private static final class QueueTransport implements ApiHttpTransport {
        private final Deque<Object> outcomes = new ArrayDeque<>();
        private final List<HttpRequest> requests = new java.util.ArrayList<>();

        private QueueTransport(Object... outcomes) {
            this.outcomes.addAll(List.of(outcomes));
        }

        @Override
        public CompletableFuture<ApiHttpResponse> sendAsync(HttpRequest request) {
            requests.add(request);
            Object outcome = outcomes.removeFirst();
            if (outcome instanceof Throwable throwable) {
                return CompletableFuture.failedFuture(throwable);
            }
            return CompletableFuture.completedFuture((ApiHttpResponse) outcome);
        }
    }
}
