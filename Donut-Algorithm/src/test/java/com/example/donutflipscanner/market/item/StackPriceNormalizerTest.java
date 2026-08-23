package com.example.donutflipscanner.market.item;

import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StackPriceNormalizerTest {
    private final ItemNormalizer itemNormalizer = new ItemNormalizer();
    private final StackPriceNormalizer priceNormalizer = new StackPriceNormalizer();

    @Test
    void calculatesCommodityUnitPricesPrecisely() {
        NormalizedItem sixtyFourIngots = itemNormalizer.normalize(ItemDescriptor.simple("iron_ingot", 64));
        NormalizedItem fourIngots = itemNormalizer.normalize(ItemDescriptor.simple("iron_ingot", 4));

        assertEquals(
                new BigDecimal("1000000"),
                priceNormalizer.unitPrice(sixtyFourIngots, new BigDecimal("64000000")).orElseThrow()
        );
        assertEquals(
                new BigDecimal("800000"),
                priceNormalizer.unitPrice(fourIngots, new BigDecimal("3200000")).orElseThrow()
        );
        assertEquals(
                new BigDecimal("3200000"),
                priceNormalizer.totalForCount(new BigDecimal("800000"), 4)
        );
    }

    @Test
    void refusesStackNormalizationForStructurallySensitiveItems() {
        NormalizedItem emptyShulker = itemNormalizer.normalize(ItemDescriptor.simple("shulker_box", 1));
        assertTrue(priceNormalizer.unitPrice(emptyShulker, new BigDecimal("1000000")).isEmpty());
    }

    @Test
    void normalizesVisibleMetadataStacksPerItem() {
        NormalizedItem tippedArrows = itemNormalizer.normalize(ItemDescriptor.simple("tipped_arrow", 8));

        assertEquals(
                new BigDecimal("250000"),
                priceNormalizer.unitPrice(tippedArrows, new BigDecimal("2000000")).orElseThrow()
        );
    }
}
