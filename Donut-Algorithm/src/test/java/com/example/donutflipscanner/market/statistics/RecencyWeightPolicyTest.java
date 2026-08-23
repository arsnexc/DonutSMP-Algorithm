package com.example.donutflipscanner.market.statistics;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RecencyWeightPolicyTest {
    private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
    private final RecencyWeightPolicy policy = RecencyWeightPolicy.defaults();

    @Test
    void appliesEveryConfiguredAgeTierAtItsInclusiveBoundary() {
        assertWeight("1.00", Duration.ofMinutes(59));
        assertWeight("0.90", Duration.ofHours(1));
        assertWeight("0.75", Duration.ofHours(6));
        assertWeight("0.55", Duration.ofHours(24));
        assertWeight("0.35", Duration.ofDays(3));
        assertWeight("0.20", Duration.ofDays(7));
    }

    private void assertWeight(String expected, Duration age) {
        assertEquals(0, new BigDecimal(expected).compareTo(policy.weight(NOW.minus(age), NOW)));
    }
}
