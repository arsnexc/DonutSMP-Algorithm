package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.ApiConnectionStatus;

import java.util.Optional;

/**
 * A connection status provider that works without an API key.
 * Reports local/offline mode status.
 */
public final class LocalApiConnectionStatusProvider implements ApiConnectionStatusProvider {
    private static final ApiConnectionStatus LOCAL_STATUS = new ApiConnectionStatus(
            "Local Mode (No API)",
            "Offline",
            true,
            0,
            0,
            0,
            Optional.of("Running in local mode without API key")
    );

    @Override
    public ApiConnectionStatus getApiConnectionStatus() {
        return LOCAL_STATUS;
    }
}
