package com.example.donutflipscanner.market.opportunity;

import com.example.donutflipscanner.market.item.model.ItemMatchType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SupportedItemPolicyTest {
    @Test
    void livePolicyIncludesVisibleMetadataButNotUnclassifiedApproximateItems() {
        SupportedItemPolicy policy = SupportedItemPolicy.liveDefaults();

        assertTrue(policy.supports(ItemMatchType.EXACT));
        assertTrue(policy.supports(ItemMatchType.COMMODITY));
        assertTrue(policy.supports(ItemMatchType.VISIBLE_METADATA));
        assertFalse(policy.supports(ItemMatchType.APPROXIMATE));
        assertFalse(policy.supports(ItemMatchType.UNSUPPORTED));
    }

    @Test
    void conservativeDefaultRemainsExactAndCommodityOnly() {
        SupportedItemPolicy policy = SupportedItemPolicy.safeDefaults();

        assertTrue(policy.supports(ItemMatchType.EXACT));
        assertTrue(policy.supports(ItemMatchType.COMMODITY));
        assertFalse(policy.supports(ItemMatchType.VISIBLE_METADATA));
    }
}
