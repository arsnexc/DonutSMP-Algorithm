package com.example.donutflipscanner.api;

import java.util.Optional;

/**
 * A credentials provider that intentionally supplies no API key.
 * Used when running in local/offline mode without API authentication.
 */
public final class NoOpApiCredentialsProvider implements ApiCredentialsProvider {
    public static final NoOpApiCredentialsProvider INSTANCE = new NoOpApiCredentialsProvider();

    private NoOpApiCredentialsProvider() {
    }

    @Override
    public Optional<char[]> copyApiKey() {
        return Optional.empty();
    }
}
