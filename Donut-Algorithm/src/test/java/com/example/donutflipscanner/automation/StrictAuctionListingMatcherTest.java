package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.automation.model.AuctionSlotSnapshot;
import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.service.StrictAuctionListingMatcher;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StrictAuctionListingMatcherTest {
    private final StrictAuctionListingMatcher matcher = new StrictAuctionListingMatcher();

    @Test
    void acceptsOnlyTheExactSemanticListing() {
        var result = matcher.match(request(), profile(), List.of(
                slot(3, "25000", "Seller"), slot(4, "25001", "Seller")
        ));
        assertEquals(3, result.slot().orElseThrow().slotId());
    }

    @Test
    void listingKeyLoreIsOptionalWhenTheProfileDoesNotExposeIt() {
        AuctionInteractionProfile withoutListingKey = new AuctionInteractionProfile(
                "private-test", "privateah seller {seller}", "Private Auction Results",
                0, 8, "Confirm Private Purchase", 4, "privateah sell {price}",
                "Private Listing Created", "", "Price: ", "Seller: "
        );
        AuctionSlotSnapshot visible = new AuctionSlotSnapshot(
                3, "minecraft:diamond_leggings", 1, "fingerprint",
                List.of("Price: $25000", "Seller: Seller")
        );

        assertEquals(3, matcher.match(request(), withoutListingKey, List.of(visible))
                .slot().orElseThrow().slotId());
    }

    @Test
    void rejectsWrongPriceSellerIdAndAmbiguity() {
        assertTrue(matcher.match(request(), profile(), List.of(slot(3, "25001", "Seller"))).slot().isEmpty());
        assertTrue(matcher.match(request(), profile(), List.of(slot(3, "25000", "Other"))).slot().isEmpty());
        assertTrue(matcher.match(request(), profile(), List.of(
                slot(3, "25000", "Seller"), slot(4, "25,000", "Seller")
        )).slot().isEmpty());
    }

    @Test
    void rejectsComponentFingerprintMismatch() {
        AuctionSlotSnapshot wrongComponents = new AuctionSlotSnapshot(
                3, "minecraft:diamond_leggings", 1, "different-fingerprint",
                List.of("Listing: listing-1", "Price: $25000", "Seller: Seller")
        );

        assertTrue(matcher.match(request(), profile(), List.of(wrongComponents)).slot().isEmpty());

        AuctionSlotSnapshot wrongItemAndCount = new AuctionSlotSnapshot(
                3, "minecraft:diamond_chestplate", 2, "fingerprint",
                List.of("Listing: listing-1", "Price: $25000", "Seller: Seller")
        );
        assertTrue(matcher.match(request(), profile(), List.of(wrongItemAndCount)).slot().isEmpty());
    }

    @Test
    void findsLaterPagesAndRejectsCrossPageAmbiguity() {
        var later = matcher.matchPages(request(), pagedProfile(), List.of(
                List.of(slot(3, "25001", "Seller")),
                List.of(slot(3, "25000", "Seller"))
        ));
        assertEquals(1, later.match().orElseThrow().pageIndex());

        var ambiguous = matcher.matchPages(request(), pagedProfile(), List.of(
                List.of(slot(3, "25000", "Seller")),
                List.of(slot(4, "25,000", "Seller"))
        ));
        assertTrue(ambiguous.match().isEmpty());
    }

    private static AuctionSlotSnapshot slot(int id, String price, String seller) {
        return new AuctionSlotSnapshot(id, "minecraft:diamond_leggings", 1, "fingerprint",
                List.of("Listing: listing-1", "Price: $" + price, "Seller: " + seller));
    }

    private static AuctionInteractionProfile pagedProfile() {
        return new AuctionInteractionProfile(
                "private-test", "privateah find {seller}", "Private Auction Results",
                0, 8, "Confirm Private Purchase", 4, "privateah sell {price}",
                "Private Listing Created", "Listing: ", "Price: ", "Seller: ",
                50, 48, 3, List.of("Click to buy")
        );
    }

    private static AuctionInteractionProfile profile() {
        return new AuctionInteractionProfile(
                "private-test", "privateah find {listing_key}", "Private Auction Results",
                0, 8, "Confirm Private Purchase", 4, "privateah sell {price}",
                "Private Listing Created", "Listing: ", "Price: ", "Seller: "
        );
    }

    private static TradeExecutionRequest request() {
        return new TradeExecutionRequest(
                "execution", "opportunity", "listing-1", "fingerprint",
                "minecraft:diamond_leggings", 1, Optional.of("Seller"),
                new BigDecimal("25000"), new BigDecimal("66500"), new BigDecimal("37000"),
                new BigDecimal("149"), 60, 8, MarketRiskLevel.LOW, Instant.now(),
                new BigDecimal("25000"), 30, AutomationMode.DRY_RUN
        );
    }
}
