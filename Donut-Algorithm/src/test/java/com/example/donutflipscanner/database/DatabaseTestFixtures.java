package com.example.donutflipscanner.database;

import com.example.donutflipscanner.database.entity.ItemFingerprintEntity;
import com.example.donutflipscanner.database.entity.ListingEntity;
import com.example.donutflipscanner.database.entity.ListingState;
import com.example.donutflipscanner.database.entity.OpportunityEntity;
import com.example.donutflipscanner.database.entity.SaleEntity;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

final class DatabaseTestFixtures {
    static final Instant BASE_TIME = Instant.parse("2026-08-03T12:00:00Z");
    static final String FINGERPRINT = "fp:netherite-ingot";

    private DatabaseTestFixtures() {
    }

    static ItemFingerprintEntity fingerprint() {
        return new ItemFingerprintEntity(
                FINGERPRINT,
                "minecraft:netherite_ingot",
                "COMMODITY",
                "{\"id\":\"minecraft:netherite_ingot\"}",
                BASE_TIME
        );
    }

    static ListingEntity listing(String key, BigDecimal price, Instant lastSeen) {
        return new ListingEntity(
                key,
                Optional.of("remote-" + key),
                Optional.of("seller-uuid"),
                Optional.of("Seller"),
                FINGERPRINT,
                "minecraft:netherite_ingot",
                4,
                price,
                Optional.of(price.divide(BigDecimal.valueOf(4))),
                BASE_TIME,
                lastSeen,
                Optional.empty(),
                Optional.empty(),
                ListingState.ACTIVE,
                0,
                Optional.of("{\"fixture\":true}")
        );
    }

    static SaleEntity sale(String key, String fingerprint, BigDecimal price, Instant soldAt) {
        return new SaleEntity(
                key,
                Optional.empty(),
                Optional.of("seller-uuid"),
                Optional.of("Seller"),
                Optional.empty(),
                Optional.empty(),
                fingerprint,
                "minecraft:netherite_ingot",
                4,
                price,
                Optional.of(price.divide(BigDecimal.valueOf(4))),
                soldAt,
                BASE_TIME.plusSeconds(120),
                Optional.of("{\"fixture\":true}")
        );
    }

    static OpportunityEntity opportunity(String listingKey) {
        return new OpportunityEntity(
                "opportunity-1",
                listingKey,
                FINGERPRINT,
                BASE_TIME.plusSeconds(30),
                new BigDecimal("14200000"),
                new BigDecimal("20800000"),
                new BigDecimal("6600000"),
                new BigDecimal("46.4788732394"),
                new BigDecimal("87"),
                "NEW",
                Optional.empty(),
                Optional.of("{\"explanation\":\"fixture only\"}"),
                "test-v1"
        );
    }
}
