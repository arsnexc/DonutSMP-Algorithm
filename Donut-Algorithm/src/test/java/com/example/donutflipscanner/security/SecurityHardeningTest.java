package com.example.donutflipscanner.security;

import com.example.donutflipscanner.api.ApiResponseException;
import com.example.donutflipscanner.api.ApiResponseValidator;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SecurityHardeningTest {
    @Test
    void redactsBearerValuesAssignmentsAndPersonalPaths() {
        String sanitized = SensitiveDataSanitizer.sanitize(
                "Authorization: Bearer super-secret api_key=another-secret "
                        + "C:\\Users\\Alice\\Documents\\debug.txt"
        );
        assertFalse(sanitized.contains("super-secret"));
        assertFalse(sanitized.contains("another-secret"));
        assertFalse(sanitized.contains("Alice"));
        assertTrue(sanitized.contains("[REDACTED]"));
    }

    @Test
    void rejectsDeepJsonBeforeTreeAllocation() {
        String deep = "[".repeat(25) + "0" + "]".repeat(25);
        assertThrows(IllegalArgumentException.class, () -> JsonSafety.validateBounds(deep, 1_024, 24));
        assertThrows(ApiResponseException.class, () -> new ApiResponseValidator(1_024)
                .parseAuctionPage(deep, 1));
    }
}
