package com.example.donutflipscanner.command;

import com.example.donutflipscanner.data.FlipOpportunity;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuctionSearchCommandTest {
    @Test
    void buildsSearchCommandFromVanillaItemIdentifier() {
        assertEquals("/ah diamond_sword",
                AuctionSearchCommand.forItem("minecraft:diamond_sword", "Diamond Sword"));
    }

    @Test
    void sanitizesUntrustedSearchTextInsteadOfCreatingExtraCommands() {
        assertEquals("/ah diamond_sword_ah_sell_1",
                AuctionSearchCommand.forItem("minecraft:diamond_sword\n/ah sell 1", "Diamond Sword"));
    }

    @Test
    void buildsExactSellCommandFromOpportunityFairValueTarget() {
        FlipOpportunity opportunity = new FlipOpportunity(
                "sell", "minecraft:diamond_block", "Diamond Block", 4, 14_200_000L, 6_600_000L
        );

        assertEquals("/ah sell 20800000", AuctionSearchCommand.sellAtTargetPrice(opportunity));
    }

    @Test
    void copiesOnlyHighestRankedNewListingFromEachSnapshot() {
        List<String> clipboardWrites = new ArrayList<>();
        OpportunityClipboardTracker tracker = new OpportunityClipboardTracker(clipboardWrites::add);
        FlipOpportunity first = opportunity("one", "listing-one", "minecraft:netherite_ingot");
        FlipOpportunity second = opportunity("two", "listing-two", "minecraft:diamond_block");

        assertEquals("/ah netherite_ingot",
                tracker.copyFirstNew(List.of(first, second)).orElseThrow());
        assertTrue(tracker.copyFirstNew(List.of(first, second)).isEmpty());

        FlipOpportunity third = opportunity("three", "listing-three", "minecraft:elytra");
        assertEquals("/ah elytra", tracker.copyFirstNew(List.of(third, first, second)).orElseThrow());
        assertEquals(List.of("/ah netherite_ingot", "/ah elytra"), clipboardWrites);
    }

    private static FlipOpportunity opportunity(String id, String listingKey, String itemId) {
        FlipOpportunity base = new FlipOpportunity(id, itemId, "Item", 1, 50, 40);
        return new FlipOpportunity(
                base.opportunityId(), listingKey, base.itemFingerprint(), base.itemId(), base.itemName(),
                base.count(), base.listingPrice(), base.fairValue(), base.estimatedProfit(), base.roiPercent(),
                base.confidencePercent(), base.comparableSales(), base.riskLevel(), base.recentLowPrice(),
                base.recentHighPrice(), base.liquidity(), base.listingAge(), base.seller(), base.state(),
                base.detectedAt(), base.lastVerifiedAt(), base.explanation(), base.warnings()
        );
    }
}
