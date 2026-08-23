package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.TradeExecutionRequest;
import com.example.donutflipscanner.automation.service.AuctionCommandRenderer;
import com.example.donutflipscanner.market.risk.MarketRiskLevel;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuctionCommandRendererTest {
    private final AuctionCommandRenderer renderer = new AuctionCommandRenderer();

    @Test
    void rendersSearchPlaceholdersAndWholeSellPriceWithoutLeadingSlash() {
        assertEquals(
                "privateah Seller search Diamond Leggings 25000",
                renderer.search(profile(), request())
        );
        assertEquals("privateah sell 66500", renderer.listing(profile(), new BigDecimal("66500")));
    }

    @Test
    void rejectsUnsafeValuesUnknownPlaceholdersAndFractionalPrices() {
        TradeExecutionRequest unsafeSeller = request(Optional.of("Seller\n/op"));
        assertThrows(IllegalArgumentException.class, () -> renderer.search(profile(), unsafeSeller));
        assertThrows(IllegalArgumentException.class,
                () -> renderer.listing(profile(), new BigDecimal("10.5")));

        AuctionInteractionProfile unknown = new AuctionInteractionProfile(
                "test", "privateah {unknown}", "Results", 0, 8,
                "Confirm", 4, "privateah sell {price}", "Created",
                "", "Price: ", "Seller: "
        );
        assertThrows(IllegalArgumentException.class, () -> renderer.search(unknown, request()));
    }

    private static AuctionInteractionProfile profile() {
        return new AuctionInteractionProfile(
                "test", "privateah {seller} search {item_name} {price}", "Results", 0, 8,
                "Confirm", 4, "privateah sell {price}", "Created",
                "", "Price: ", "Seller: "
        );
    }

    private static TradeExecutionRequest request() {
        return request(Optional.of("Seller"));
    }

    private static TradeExecutionRequest request(Optional<String> seller) {
        return new TradeExecutionRequest(
                "execution", "opportunity", "listing", "fingerprint",
                "minecraft:diamond_leggings", 1, seller,
                new BigDecimal("25000"), new BigDecimal("66500"), new BigDecimal("41500"),
                new BigDecimal("166"), 80, 12, MarketRiskLevel.LOW, Instant.now(),
                new BigDecimal("25000"), 30, AutomationMode.DRY_RUN,
                Optional.of("Diamond Leggings"), Optional.of("{}")
        );
    }
}
