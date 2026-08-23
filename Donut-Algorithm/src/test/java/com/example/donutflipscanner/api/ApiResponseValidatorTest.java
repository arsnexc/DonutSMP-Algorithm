package com.example.donutflipscanner.api;

import com.example.donutflipscanner.api.model.ApiAuctionPage;
import com.example.donutflipscanner.api.model.ApiTransactionPage;
import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ApiResponseValidatorTest {
    private final ApiResponseValidator validator = new ApiResponseValidator(1_000_000);

    @Test
    void parsesValidAuctionResponse() {
        ApiAuctionPage page = validator.parseAuctionPage(FixtureLoader.read("auction-valid.json"), 3);

        assertEquals(200, page.status());
        assertEquals(1, page.listings().size());
        assertEquals("minecraft:diamond_sword", page.listings().getFirst().item().orElseThrow().id().orElseThrow());
        assertEquals(new BigDecimal("14200000"), page.listings().getFirst().price().orElseThrow());
        assertEquals(2, page.listings().getFirst().item().orElseThrow().itemData().orElseThrow().enchantments().size());
        assertEquals("minecraft:sharpness", page.listings().getFirst().item().orElseThrow()
                .itemData().orElseThrow().enchantments().getFirst().id());
    }

    @Test
    void parsesValidTransactionResponseAndTimestamp() {
        ApiTransactionPage page = validator.parseTransactionPage(FixtureLoader.read("transaction-valid.json"), 2);

        assertEquals(1, page.transactions().size());
        assertEquals(4, page.transactions().getFirst().item().orElseThrow().count().orElseThrow());
        assertEquals(Instant.parse("2025-01-01T00:00:00Z"), page.transactions().getFirst().soldAt().orElseThrow());
    }

    @Test
    void acceptsMissingOptionalFields() {
        ApiAuctionPage page = validator.parseAuctionPage(FixtureLoader.read("auction-missing-optional.json"), 1);

        assertTrue(page.listings().getFirst().seller().isEmpty());
        assertTrue(page.listings().getFirst().timeLeft().isEmpty());
        assertTrue(page.listings().getFirst().item().orElseThrow().count().isEmpty());
        assertTrue(page.listings().getFirst().item().orElseThrow().lore().isEmpty());
    }

    @Test
    void treatsBlankDisplayAndEmptyTrimObjectAsAbsent() {
        ApiAuctionPage page = validator.parseAuctionPage(
                FixtureLoader.read("auction-empty-trim.json"), 1
        );
        var item = page.listings().getFirst().item().orElseThrow();

        assertTrue(item.displayName().isEmpty());
        assertTrue(item.itemData().orElseThrow().trim().isEmpty());
        assertEquals(ItemMatchType.COMMODITY, new ItemNormalizer().normalize(item).matchQuality().matchType());
    }

    @Test
    void rejectsInvalidJson() {
        assertThrows(ApiResponseException.class,
                () -> validator.parseAuctionPage(FixtureLoader.read("invalid.json"), 1));
    }

    @Test
    void preservesVeryLargePricesAndIgnoresUnknownFields() {
        ApiAuctionPage page = validator.parseAuctionPage(FixtureLoader.read("auction-large-price.json"), 1);

        assertEquals(
                new BigDecimal("99999999999999999999999999999999999999999999999999"),
                page.listings().getFirst().price().orElseThrow()
        );
    }

    @Test
    void reportsUnknownItemMetadataWithoutInterpretingIt() {
        ApiAuctionPage page = validator.parseAuctionPage(
                FixtureLoader.read("auction-unknown-item-metadata.json"), 1
        );

        assertEquals(
                java.util.List.of("future_item_component"),
                page.listings().getFirst().item().orElseThrow().unrecognizedFields()
        );
    }

    @Test
    void validatesButIgnoresToolDurabilityForMarketMatching() {
        String first = """
                {"status":200,"result":[{"item":{"id":"minecraft:diamond_pickaxe","count":1,
                "damage":1,"max_damage":1561},"price":1000,
                "seller":{"name":"Miner","uuid":"miner-uuid"}}]}
                """;
        String second = """
                {"status":200,"result":[{"item":{"id":"minecraft:diamond_pickaxe","count":1,
                "damage":1500,"max_damage":1561},"price":1000,
                "seller":{"name":"Miner","uuid":"miner-uuid"}}]}
                """;

        var firstItem = validator.parseAuctionPage(first, 1).listings().getFirst().item().orElseThrow();
        var secondItem = validator.parseAuctionPage(second, 1).listings().getFirst().item().orElseThrow();

        assertTrue(firstItem.unrecognizedFields().isEmpty());
        assertEquals(
                new ItemNormalizer().normalize(firstItem).fingerprint(),
                new ItemNormalizer().normalize(secondItem).fingerprint()
        );
    }

    @Test
    void exposesOnlyDocumentedPaginationFacts() {
        ApiAuctionPage auctionPage = validator.parseAuctionPage(FixtureLoader.read("auction-valid.json"), 7);
        ApiTransactionPage transactionPage = validator.parseTransactionPage(FixtureLoader.read("transaction-valid.json"), 4);

        assertEquals(7, auctionPage.pagination().requestedPage());
        assertEquals(1, auctionPage.pagination().returnedItems());
        assertTrue(auctionPage.pagination().documentedPageSize().isEmpty());
        assertTrue(auctionPage.pagination().documentedMaximumPage().isEmpty());

        assertEquals(4, transactionPage.pagination().requestedPage());
        assertEquals(100, transactionPage.pagination().documentedPageSize().orElseThrow());
        assertEquals(10, transactionPage.pagination().documentedMaximumPage().orElseThrow());
        assertFalse(transactionPage.transactions().isEmpty());
    }

    @Test
    void parsesDocumentedPlayerMoneyStringAsAnExactBalance() {
        var stats = validator.parsePlayerStats(
                "{\"status\":200,\"result\":{\"money\":\"$1.25B\",\"kills\":\"4\"}}"
        );

        assertEquals(200, stats.status());
        assertEquals(new BigDecimal("1250000000"), stats.money());
    }

    @Test
    void rejectsMissingOrMalformedPlayerBalance() {
        assertThrows(ApiResponseException.class,
                () -> validator.parsePlayerStats("{\"status\":200,\"result\":{}}"));
        assertThrows(ApiResponseException.class,
                () -> validator.parsePlayerStats(
                        "{\"status\":200,\"result\":{\"money\":\"Balance: $1M\"}}"
                ));
    }
}
