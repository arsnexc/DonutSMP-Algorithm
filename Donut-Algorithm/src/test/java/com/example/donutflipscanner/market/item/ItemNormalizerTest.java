package com.example.donutflipscanner.market.item;

import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.market.item.model.ArmorTrimDescriptor;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.ItemEnchantmentDescriptor;
import com.example.donutflipscanner.market.item.model.ItemMatchType;
import com.example.donutflipscanner.market.item.model.ItemNormalizationIssue;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemNormalizerTest {
    private final ItemNormalizer normalizer = new ItemNormalizer();

    @Test
    void createsBasicCommodityFingerprintWithoutStackSizeInIdentity() {
        NormalizedItem stackOfFour = normalizer.normalize(ItemDescriptor.simple("NETHERITE_INGOT", 4));
        NormalizedItem stackOfSixtyFour = normalizer.normalize(ItemDescriptor.simple(
                "minecraft:netherite_ingot", 64
        ));

        assertEquals("minecraft:netherite_ingot", stackOfFour.itemId());
        assertEquals(ItemMatchType.COMMODITY, stackOfFour.matchQuality().matchType());
        assertEquals(75, stackOfFour.matchQuality().score());
        assertEquals(stackOfFour.fingerprint(), stackOfSixtyFour.fingerprint());
        assertEquals(
                "{\"formatVersion\":1,\"matchType\":\"COMMODITY\",\"itemId\":\"minecraft:netherite_ingot\","
                        + "\"customName\":null,\"lore\":[],\"enchantments\":[],\"trim\":null,\"contents\":[],"
                        + "\"contentsTruncated\":false,\"unrecognizedFields\":[]}",
                stackOfFour.fingerprint().canonicalMetadata()
        );
        assertEquals(
                "9c287234bd49f75c8084a1f5f0963c605b1560789fa321c94fa339bbae39ae85",
                stackOfFour.fingerprint().sha256()
        );
        assertTrue(stackOfFour.commodityPriceEligible());
    }

    @Test
    void allVanillaOreBlocksAreExplicitCommodityMarkets() {
        for (String itemId : MarketItemFamilyPolicy.oreItemIds()) {
            NormalizedItem ore = normalizer.normalize(ItemDescriptor.simple(itemId, 16));
            assertEquals(ItemMatchType.COMMODITY, ore.matchQuality().matchType(), itemId);
            assertTrue(ore.commodityPriceEligible(), itemId);
        }
    }

    @Test
    void liveProfileAcceptsOrdinaryVanillaCategoriesEqually() {
        ItemNormalizer live = new ItemNormalizer(
                SafeItemCategoryRegistry.liveDefaults(), ItemNormalizer.DEFAULT_MAXIMUM_CONTAINER_DEPTH
        );

        for (String itemId : List.of(
                "stone", "oak_log", "glass_pane", "bread", "cooked_beef",
                "repeater", "hopper", "rail", "painting", "music_disc_cat",
                "smithing_table", "netherite_upgrade_smithing_template"
        )) {
            NormalizedItem item = live.normalize(ItemDescriptor.simple(itemId, 8));
            assertEquals(ItemMatchType.COMMODITY, item.matchQuality().matchType(), itemId);
        }

        assertEquals(ItemMatchType.APPROXIMATE,
                normalizer.normalize(ItemDescriptor.simple("stone", 8)).matchQuality().matchType());
    }

    @Test
    void broadLiveProfileDoesNotBypassMetadataSensitiveFamilies() {
        ItemNormalizer live = new ItemNormalizer(
                SafeItemCategoryRegistry.liveDefaults(), ItemNormalizer.DEFAULT_MAXIMUM_CONTAINER_DEPTH
        );

        assertEquals(ItemMatchType.VISIBLE_METADATA,
                live.normalize(ItemDescriptor.simple("diamond_pickaxe", 1)).matchQuality().matchType());
        assertEquals(ItemMatchType.VISIBLE_METADATA,
                live.normalize(ItemDescriptor.simple("potion", 1)).matchQuality().matchType());
        assertEquals(ItemMatchType.UNSUPPORTED,
                live.normalize(ItemDescriptor.simple("player_head", 1)).matchQuality().matchType());
        assertEquals(ItemMatchType.UNSUPPORTED,
                live.normalize(ItemDescriptor.simple("donut:collectible", 1)).matchQuality().matchType());
    }

    @Test
    void enchantmentOrderDoesNotChangeFingerprint() {
        ItemDescriptor first = sword(List.of(
                new ItemEnchantmentDescriptor("minecraft:unbreaking", 3),
                new ItemEnchantmentDescriptor("sharpness", 5)
        ));
        ItemDescriptor reordered = sword(List.of(
                new ItemEnchantmentDescriptor("minecraft:sharpness", 5),
                new ItemEnchantmentDescriptor("minecraft:unbreaking", 3)
        ));

        NormalizedItem normalized = normalizer.normalize(first);
        assertEquals(normalized.fingerprint(), normalizer.normalize(reordered).fingerprint());
        assertEquals("minecraft:sharpness", normalized.enchantments().getFirst().id());
    }

    @Test
    void differentEnchantmentLevelsProduceDifferentFingerprints() {
        NormalizedItem sharpnessFive = normalizer.normalize(sword(List.of(
                new ItemEnchantmentDescriptor("sharpness", 5)
        )));
        NormalizedItem sharpnessFour = normalizer.normalize(sword(List.of(
                new ItemEnchantmentDescriptor("sharpness", 4)
        )));

        assertNotEquals(sharpnessFive.fingerprint().sha256(), sharpnessFour.fingerprint().sha256());
    }

    @Test
    void renamedItemDoesNotMatchItsNormalCommodity() {
        NormalizedItem normal = normalizer.normalize(descriptor(
                "minecraft:diamond", 1, Optional.of("Diamond"), List.of(), List.of(), Optional.empty(), List.of(), List.of()
        ));
        NormalizedItem renamed = normalizer.normalize(descriptor(
                "minecraft:diamond", 1, Optional.of("Lucky Diamond"), List.of(), List.of(), Optional.empty(), List.of(), List.of()
        ));
        NormalizedItem formatted = normalizer.normalize(descriptor(
                "minecraft:diamond", 1, Optional.of("\u00a7CDiamond"), List.of(), List.of(), Optional.empty(), List.of(), List.of()
        ));

        assertTrue(normal.customName().isEmpty());
        assertEquals(ItemMatchType.COMMODITY, normal.matchQuality().matchType());
        assertEquals("Lucky Diamond", renamed.customName().orElseThrow());
        assertEquals(ItemMatchType.EXACT, renamed.matchQuality().matchType());
        assertNotEquals(normal.fingerprint(), renamed.fingerprint());
        assertEquals("\u00a7cDiamond", formatted.customName().orElseThrow());
        assertNotEquals(normal.fingerprint(), formatted.fingerprint());
    }

    @Test
    void blankDisplayNameAndCompletelyEmptyTrimAreIgnored() {
        NormalizedItem item = normalizer.normalize(descriptor(
                "minecraft:netherite_ingot", 4, Optional.of("   "), List.of(), List.of(),
                Optional.of(new ArmorTrimDescriptor(Optional.of(""), Optional.of(" "), List.of())),
                List.of(), List.of()
        ));

        assertTrue(item.customName().isEmpty());
        assertTrue(item.armorTrim().isEmpty());
        assertEquals(ItemMatchType.COMMODITY, item.matchQuality().matchType());
        assertFalse(item.matchQuality().issues().contains(ItemNormalizationIssue.INCOMPLETE_ARMOR_TRIM));
    }

    @Test
    void loreLineEndingsNormalizeButLoreDifferencesRemainDistinct() {
        NormalizedItem windowsLines = normalizer.normalize(descriptor(
                "minecraft:diamond", 1, Optional.empty(), List.of("Line one\r\nLine two"),
                List.of(), Optional.empty(), List.of(), List.of()
        ));
        NormalizedItem unixLines = normalizer.normalize(descriptor(
                "minecraft:diamond", 1, Optional.empty(), List.of("Line one\nLine two"),
                List.of(), Optional.empty(), List.of(), List.of()
        ));
        NormalizedItem differentLore = normalizer.normalize(descriptor(
                "minecraft:diamond", 1, Optional.empty(), List.of("Different"),
                List.of(), Optional.empty(), List.of(), List.of()
        ));

        assertEquals(windowsLines.fingerprint(), unixLines.fingerprint());
        assertNotEquals(windowsLines.fingerprint(), differentLore.fingerprint());
    }

    @Test
    void damageableItemsUseGuardedVisibleMetadataMatching() {
        NormalizedItem sword = normalizer.normalize(sword(List.of(
                new ItemEnchantmentDescriptor("sharpness", 5)
        )));

        assertEquals(ItemMatchType.VISIBLE_METADATA, sword.matchQuality().matchType());
        assertEquals(60, sword.matchQuality().score());
        assertTrue(sword.matchQuality().issues().contains(ItemNormalizationIssue.DURABILITY_NOT_EXPOSED));
        assertTrue(sword.matchQuality().issues().contains(ItemNormalizationIssue.VISIBLE_METADATA_ONLY));
    }

    @Test
    void requestedWeaponsToolsAndArmorUseVisibleMetadataMatching() {
        for (String itemId : List.of(
                "diamond_sword", "netherite_pickaxe", "iron_axe", "diamond_shovel", "golden_hoe",
                "netherite_helmet", "diamond_chestplate", "iron_leggings", "leather_boots",
                "bow", "crossbow", "trident", "shield", "elytra", "mace", "shears", "brush",
                "wolf_armor", "diamond_horse_armor"
        )) {
            NormalizedItem item = normalizer.normalize(ItemDescriptor.simple(itemId, 1));
            assertEquals(ItemMatchType.VISIBLE_METADATA, item.matchQuality().matchType(), itemId);
            assertTrue(item.matchQuality().issues().contains(ItemNormalizationIssue.VISIBLE_METADATA_ONLY), itemId);
        }
    }

    @Test
    void requestedBooksMapsPotionsAndArrowsUseVisibleMetadataMatching() {
        for (String itemId : List.of(
                "enchanted_book", "written_book", "map", "filled_map", "potion",
                "splash_potion", "lingering_potion", "tipped_arrow"
        )) {
            NormalizedItem item = normalizer.normalize(ItemDescriptor.simple(itemId, 4));
            assertEquals(ItemMatchType.VISIBLE_METADATA, item.matchQuality().matchType(), itemId);
            assertTrue(item.unitPriceEligible(), itemId);
            assertTrue(item.matchQuality().issues().contains(ItemNormalizationIssue.VISIBLE_METADATA_ONLY), itemId);
        }
    }

    @Test
    void armorTrimDifferencesProduceDifferentFingerprints() {
        ItemDescriptor quartzTrim = descriptor(
                "minecraft:netherite_boots", 1, Optional.of("Netherite Boots"), List.of(), List.of(),
                Optional.of(new ArmorTrimDescriptor(
                        Optional.of("quartz"), Optional.of("spire"), List.of()
                )), List.of(), List.of()
        );
        ItemDescriptor redstoneTrim = descriptor(
                "minecraft:netherite_boots", 1, Optional.of("Netherite Boots"), List.of(), List.of(),
                Optional.of(new ArmorTrimDescriptor(
                        Optional.of("redstone"), Optional.of("spire"), List.of()
                )), List.of(), List.of()
        );

        NormalizedItem first = normalizer.normalize(quartzTrim);
        NormalizedItem second = normalizer.normalize(redstoneTrim);
        assertEquals(ItemMatchType.VISIBLE_METADATA, first.matchQuality().matchType());
        assertNotEquals(first.fingerprint(), second.fingerprint());
    }

    @Test
    void emptyAndFilledShulkerBoxesNeverMatch() {
        NormalizedItem empty = normalizer.normalize(container(List.of()));
        NormalizedItem filled = normalizer.normalize(container(List.of(ItemDescriptor.simple("diamond", 4))));

        assertEquals(ItemMatchType.EXACT, empty.matchQuality().matchType());
        assertEquals(ItemMatchType.UNSUPPORTED, filled.matchQuality().matchType());
        assertTrue(filled.matchQuality().issues().contains(ItemNormalizationIssue.FILLED_CONTAINER_CAUTION));
        assertNotEquals(empty.fingerprint(), filled.fingerprint());
    }

    @Test
    void containerOrderAndEquivalentStackSplitsNormalizeDeterministically() {
        NormalizedItem ordered = normalizer.normalize(container(List.of(
                ItemDescriptor.simple("diamond", 2),
                ItemDescriptor.simple("emerald", 3)
        )));
        NormalizedItem reordered = normalizer.normalize(container(List.of(
                ItemDescriptor.simple("emerald", 3),
                ItemDescriptor.simple("diamond", 2)
        )));
        NormalizedItem splitStacks = normalizer.normalize(container(List.of(
                ItemDescriptor.simple("diamond", 2),
                ItemDescriptor.simple("diamond", 3)
        )));
        NormalizedItem combinedStack = normalizer.normalize(container(List.of(
                ItemDescriptor.simple("diamond", 5)
        )));
        NormalizedItem differentContents = normalizer.normalize(container(List.of(
                ItemDescriptor.simple("diamond", 6)
        )));

        assertEquals(ordered.fingerprint(), reordered.fingerprint());
        assertEquals(splitStacks.fingerprint(), combinedStack.fingerprint());
        assertEquals(1, splitStacks.contents().size());
        assertEquals(5, splitStacks.contents().getFirst().count().orElseThrow());
        assertNotEquals(combinedStack.fingerprint(), differentContents.fingerprint());
    }

    @Test
    void jsonTextComponentsAndIdsCanonicalizeDeterministically() {
        ItemDescriptor first = descriptor(
                "DIAMOND", 1, Optional.of("{\"text\":\"Lucky\",\"color\":\"red\"}"),
                List.of(), List.of(), Optional.empty(), List.of(), List.of()
        );
        ItemDescriptor reorderedJson = descriptor(
                "minecraft:diamond", 1, Optional.of("{\"color\":\"red\",\"text\":\"Lucky\"}"),
                List.of(), List.of(), Optional.empty(), List.of(), List.of()
        );

        assertEquals(normalizer.normalize(first).fingerprint(), normalizer.normalize(reorderedJson).fingerprint());
    }

    @Test
    void unknownApiMetadataIsRetainedByNameAndClassifiedUnsupported() {
        ApiAuctionItem raw = new ApiAuctionItem(
                Optional.of("minecraft:diamond"),
                OptionalInt.of(1),
                Optional.empty(),
                List.of(),
                Optional.empty(),
                List.of(),
                List.of("future_component")
        );

        NormalizedItem normalized = normalizer.normalize(raw);
        assertEquals(ItemMatchType.UNSUPPORTED, normalized.matchQuality().matchType());
        assertEquals(List.of("future_component"), normalized.unrecognizedFields());
        assertTrue(normalized.fingerprint().canonicalMetadata().contains("future_component"));
    }

    @Test
    void unsafeAndServerSpecificCategoriesAreUnsupported() {
        NormalizedItem book = normalizer.normalize(ItemDescriptor.simple("writable_book", 1));
        NormalizedItem head = normalizer.normalize(ItemDescriptor.simple("player_head", 1));
        NormalizedItem serverItem = normalizer.normalize(ItemDescriptor.simple("donut:collectible", 1));

        assertEquals(ItemMatchType.UNSUPPORTED, book.matchQuality().matchType());
        assertEquals(ItemMatchType.UNSUPPORTED, head.matchQuality().matchType());
        assertEquals(ItemMatchType.UNSUPPORTED, serverItem.matchQuality().matchType());
        assertTrue(serverItem.matchQuality().issues().contains(ItemNormalizationIssue.SERVER_SPECIFIC_ITEM));
    }

    @Test
    void overDeepContainersAreRejectedWithoutTraversingIndefinitely() {
        ItemDescriptor nested = ItemDescriptor.simple("diamond", 1);
        for (int depth = 0; depth < 6; depth++) {
            nested = container(List.of(nested));
        }

        NormalizedItem normalized = new ItemNormalizer(
                SafeItemCategoryRegistry.safeDefaults(), 2
        ).normalize(nested);

        assertEquals(ItemMatchType.UNSUPPORTED, normalized.matchQuality().matchType());
        assertTrue(normalized.matchQuality().issues().contains(ItemNormalizationIssue.CONTAINER_DEPTH_EXCEEDED));
    }

    @Test
    void oversizedContainerInputIsBoundedAndUnsupported() {
        List<ItemDescriptor> excessiveContents = java.util.Collections.nCopies(
                ItemNormalizer.MAXIMUM_CONTAINER_ENTRIES + 1,
                ItemDescriptor.simple("diamond", 1)
        );

        NormalizedItem normalized = normalizer.normalize(container(excessiveContents));

        assertEquals(ItemMatchType.UNSUPPORTED, normalized.matchQuality().matchType());
        assertTrue(normalized.contentsTruncated());
        assertTrue(normalized.matchQuality().issues().contains(
                ItemNormalizationIssue.CONTAINER_ENTRY_LIMIT_EXCEEDED
        ));
    }

    @Test
    void safeCategoryRegistryCanExplicitlyAllowProviderSpecificItems() {
        SafeItemCategoryRegistry registry = SafeItemCategoryRegistry.builder()
                .addExactSafeItems(List.of("donut:collectible"))
                .build();

        NormalizedItem normalized = new ItemNormalizer(registry, 4)
                .normalize(ItemDescriptor.simple("donut:collectible", 1));

        assertEquals(ItemMatchType.EXACT, normalized.matchQuality().matchType());
        assertEquals(100, normalized.matchQuality().score());
        assertFalse(normalized.matchQuality().issues().contains(ItemNormalizationIssue.SERVER_SPECIFIC_ITEM));
    }

    private ItemDescriptor sword(List<ItemEnchantmentDescriptor> enchantments) {
        return descriptor(
                "minecraft:netherite_sword", 1, Optional.of("Netherite Sword"),
                List.of(), enchantments, Optional.empty(), List.of(), List.of()
        );
    }

    private ItemDescriptor container(List<ItemDescriptor> contents) {
        return descriptor(
                "minecraft:shulker_box", 1, Optional.of("Shulker Box"),
                List.of(), List.of(), Optional.empty(), contents, List.of()
        );
    }

    private ItemDescriptor descriptor(
            String id,
            int count,
            Optional<String> displayName,
            List<String> lore,
            List<ItemEnchantmentDescriptor> enchantments,
            Optional<ArmorTrimDescriptor> trim,
            List<ItemDescriptor> contents,
            List<String> unknownFields
    ) {
        return new ItemDescriptor(
                Optional.of(id),
                OptionalInt.of(count),
                displayName,
                lore,
                enchantments,
                trim,
                contents,
                unknownFields
        );
    }
}
