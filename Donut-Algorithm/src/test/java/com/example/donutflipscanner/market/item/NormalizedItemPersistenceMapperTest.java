package com.example.donutflipscanner.market.item;

import com.example.donutflipscanner.api.model.ApiAuctionItem;
import com.example.donutflipscanner.api.model.ApiEnchantment;
import com.example.donutflipscanner.api.model.ApiItemData;
import com.example.donutflipscanner.database.entity.ItemFingerprintEntity;
import com.example.donutflipscanner.market.item.model.NormalizedItem;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;

class NormalizedItemPersistenceMapperTest {
    @Test
    void reconstructsCanonicalItemWithRuntimeStackCount() {
        ApiAuctionItem raw = new ApiAuctionItem(
                Optional.of("minecraft:diamond_sword"),
                OptionalInt.of(1),
                Optional.of("Market Blade"),
                List.of("A real listing variant"),
                Optional.of(new ApiItemData(
                        List.of(new ApiEnchantment("minecraft:sharpness", 5)), Optional.empty()
                )),
                List.of()
        );
        NormalizedItem original = new ItemNormalizer().normalize(raw);
        ItemFingerprintEntity stored = new ItemFingerprintPersistenceMapper()
                .toEntity(original, Instant.parse("2026-08-04T00:00:00Z"));

        NormalizedItem reconstructed = new NormalizedItemPersistenceMapper().fromEntity(stored, 1);

        assertEquals(original.fingerprint(), reconstructed.fingerprint());
        assertEquals(original.itemId(), reconstructed.itemId());
        assertEquals(original.customName(), reconstructed.customName());
        assertEquals(original.enchantments(), reconstructed.enchantments());
        assertEquals(1, reconstructed.stackCount().orElseThrow());
    }
}
