package com.example.donutflipscanner.data.provider;

import com.example.donutflipscanner.data.ItemSearchResult;
import net.minecraft.registry.Registries;

import java.util.List;
import java.util.Locale;

/** Builds the Minecraft item index once; search calls never rebuild registry data. */
public final class RegistryItemSearchProvider implements ItemSearchProvider {
    private final List<ItemSearchResult> index;

    public RegistryItemSearchProvider() {
        index = Registries.ITEM.getIds().stream()
                .map(id -> new ItemSearchResult(id.toString(), ClientDataFormat.itemName(id.toString())))
                .sorted(java.util.Comparator.comparing(ItemSearchResult::displayName))
                .toList();
    }

    @Override
    public List<ItemSearchResult> search(String query) {
        String normalized = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return index;
        }
        return index.stream().filter(item -> item.itemId().contains(normalized)
                || item.displayName().toLowerCase(Locale.ROOT).contains(normalized)).toList();
    }
}
