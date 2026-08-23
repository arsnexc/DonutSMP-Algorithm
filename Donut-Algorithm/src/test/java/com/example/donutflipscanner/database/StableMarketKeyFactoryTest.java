package com.example.donutflipscanner.database;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StableMarketKeyFactoryTest {
    @Test
    void derivedListingKeyIsDeterministicAndSensitiveToIdentityFields() {
        String first = listingKey(new BigDecimal("14200000.00"), 4);
        String equivalent = listingKey(new BigDecimal("14200000"), 4);
        String changedPrice = listingKey(new BigDecimal("14200001"), 4);

        assertEquals(first, equivalent);
        assertNotEquals(first, changedPrice);
        assertTrue(first.matches("listing:[0-9a-f]{64}"));
    }

    @Test
    void remoteIdentifierTakesPrecedenceWhenPresent() {
        String first = StableMarketKeyFactory.listingKey(
                Optional.of("server-id"), "seller-a", "fp-a", BigDecimal.ONE, 1,
                Optional.empty(), Optional.empty()
        );
        String second = StableMarketKeyFactory.listingKey(
                Optional.of("server-id"), "seller-b", "fp-b", BigDecimal.TEN, 64,
                Optional.of(Instant.EPOCH), Optional.of(Instant.MAX)
        );

        assertEquals(first, second);
    }

    @Test
    void saleKeyUsesAllFallbackIdentityFields() {
        String first = StableMarketKeyFactory.saleKey(
                Optional.empty(), "seller", "buyer", "fingerprint", BigDecimal.TEN, 2, Instant.EPOCH
        );
        String changedBuyer = StableMarketKeyFactory.saleKey(
                Optional.empty(), "seller", "other", "fingerprint", BigDecimal.TEN, 2, Instant.EPOCH
        );

        assertNotEquals(first, changedBuyer);
        assertEquals(first, StableMarketKeyFactory.saleKey(
                Optional.empty(), "seller", "buyer", "fingerprint", new BigDecimal("10.0"), 2, Instant.EPOCH
        ));
    }

    private String listingKey(BigDecimal price, int count) {
        return StableMarketKeyFactory.listingKey(
                Optional.empty(),
                "seller",
                "fingerprint",
                price,
                count,
                Optional.of(Instant.parse("2026-08-03T12:00:00Z")),
                Optional.empty()
        );
    }
}
