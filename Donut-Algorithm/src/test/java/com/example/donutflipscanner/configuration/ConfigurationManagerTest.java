package com.example.donutflipscanner.configuration;

import com.example.donutflipscanner.automation.model.AutomationMode;
import com.example.donutflipscanner.automation.model.RelistPricingStrategy;
import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.market.opportunity.ItemFilterMode;
import com.example.donutflipscanner.market.opportunity.ItemFilterPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationManagerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void createsDefaultsAndRoundTripsValidatedSettings() throws Exception {
        Path path = temporaryDirectory.resolve("config.json");
        ConfigurationManager manager = new ConfigurationManager(path);
        ConfigurationLoadResult initial = manager.load();
        assertEquals(AppConfig.defaults(), initial.configuration());
        assertTrue(Files.isRegularFile(path));

        AppConfig changed = new AppConfig(
                AppConfig.CURRENT_FORMAT_VERSION, false, false, false, false, 0.75D,
                ItemFilterMode.WHITELIST_ONLY, Set.of("minecraft:diamond"), Set.of(),
                Map.of("minecraft:diamond", ItemThresholdConfig.defaults()),
                new AutomationConfig(
                        true, AutomationMode.CONFIRM_EACH, Set.of("authorized.example"),
                        new BigDecimal("25000"), new BigDecimal("50000"), 2,
                        55, 12, new BigDecimal("5000"), new BigDecimal("20"),
                        20, 15, RelistPricingStrategy.TARGET_ROI,
                        new BigDecimal("45"), new BigDecimal("10"),
                        true, true, true, false,
                        Optional.of(new AuctionInteractionProfile(
                                "private-test", "privateah find {listing_key}", "Private Results",
                                0, 8, "Confirm Private Purchase", 4,
                                 "privateah sell {price}", "Private Listing Created",
                                "", "Price: ", "Seller: ", 53, 45, 10,
                                List.of("Click to buy", "Expires: ")
                         ))
                )
        );
        manager.save(changed);
        assertEquals(changed, manager.load().configuration());
    }

    @Test
    void migratesVersionOneAndUsesDefaultsForNewFields() throws Exception {
        Path path = temporaryDirectory.resolve("legacy.json");
        Files.writeString(path, """
                {
                  "formatVersion": 1,
                  "scannerEnabled": false,
                  "guiScale": 0.75,
                  "filterMode": "ALL_EXCEPT_BLACKLIST",
                  "blacklistedItems": ["minecraft:written_book"]
                }
                """, StandardCharsets.UTF_8);

        AppConfig migrated = new ConfigurationManager(path).load().configuration();
        assertEquals(AppConfig.CURRENT_FORMAT_VERSION, migrated.formatVersion());
        assertEquals(0.75D, migrated.interfaceScale());
        assertFalse(migrated.scannerEnabled());
        assertTrue(migrated.animationsEnabled());
        assertTrue(migrated.notificationsEnabled());
        assertEquals(AutomationConfig.defaults(), migrated.automation());
        assertEquals(Set.of("minecraft:written_book"), migrated.blacklistedItems());
    }

    @Test
    void olderInteractionProfilesDefaultToSinglePageWithoutLosingCompatibility() throws Exception {
        Path path = temporaryDirectory.resolve("legacy-profile.json");
        Files.writeString(path, """
                {
                  "formatVersion": 3,
                  "automation": {
                    "interactionProfile": {
                      "profileId": "legacy-private",
                      "searchCommandTemplate": "privateah find {listing_key}",
                      "resultsScreenTitle": "Results",
                      "firstResultSlot": 0,
                      "lastResultSlot": 8,
                      "purchaseConfirmationTitle": "Confirm",
                      "purchaseConfirmationSlot": 4,
                      "listingCommandTemplate": "privateah sell {price}",
                      "listingCreatedTitle": "Created",
                      "listingKeyLorePrefix": "Listing: ",
                      "priceLorePrefix": "Price: ",
                      "sellerLorePrefix": "Seller: "
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        AuctionInteractionProfile profile = new ConfigurationManager(path).load().configuration()
                .automation().interactionProfile().orElseThrow();

        assertEquals(1, profile.maximumPages());
        assertEquals(-1, profile.nextPageSlot());
        assertEquals(-1, profile.previousPageSlot());
        assertTrue(profile.ignoredLorePrefixes().isEmpty());
    }

    @Test
    void backsUpCorruptionBeforeReplacingItWithDefaults() throws Exception {
        Path path = temporaryDirectory.resolve("corrupt.json");
        byte[] original = "{not-valid-json".getBytes(StandardCharsets.UTF_8);
        Files.write(path, original);

        ConfigurationLoadResult result = new ConfigurationManager(path).load();

        assertTrue(result.recoveredFromCorruption());
        assertTrue(result.backupPath().isPresent());
        assertEquals(new String(original, StandardCharsets.UTF_8),
                Files.readString(result.backupPath().orElseThrow(), StandardCharsets.UTF_8));
        assertEquals(AppConfig.defaults(), result.configuration());
    }

    @Test
    void filterImportValidatesIdsAndCannotApplyUnrelatedSettings() throws Exception {
        Path path = temporaryDirectory.resolve("filters.json");
        Files.writeString(path, """
                {
                  "formatVersion": 2,
                  "scannerEnabled": false,
                  "filterMode": "WHITELIST_ONLY",
                  "whitelistedItems": ["minecraft:netherite_ingot"],
                  "blacklistedItems": []
                }
                """, StandardCharsets.UTF_8);

        ItemFilterPolicy filters = new FilterConfigurationIO().read(path);
        assertEquals(ItemFilterMode.WHITELIST_ONLY, filters.mode());
        assertEquals(Set.of("minecraft:netherite_ingot"), filters.whitelistedItemIds());
    }
}
