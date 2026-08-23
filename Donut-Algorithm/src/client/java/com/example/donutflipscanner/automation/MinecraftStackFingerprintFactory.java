package com.example.donutflipscanner.automation;

import com.example.donutflipscanner.automation.model.AuctionInteractionProfile;
import com.example.donutflipscanner.market.item.ItemNormalizer;
import com.example.donutflipscanner.market.item.model.ArmorTrimDescriptor;
import com.example.donutflipscanner.market.item.model.ItemDescriptor;
import com.example.donutflipscanner.market.item.model.ItemEnchantmentDescriptor;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.item.ItemStack;
import net.minecraft.item.equipment.trim.ArmorTrim;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.TreeMap;

/** Converts an in-game stack into the same provider-neutral fingerprint used for API items. */
final class MinecraftStackFingerprintFactory {
    private static final int MAXIMUM_CONTAINER_DEPTH = 4;

    private final ItemNormalizer normalizer = new ItemNormalizer();

    String fingerprint(ItemStack stack, AuctionInteractionProfile profile) {
        return normalizer.normalize(descriptor(stack, profile, 0)).fingerprint().sha256();
    }

    List<String> visibleLore(ItemStack stack) {
        LoreComponent lore = stack.get(DataComponentTypes.LORE);
        return lore == null ? List.of() : lore.lines().stream()
                .map(value -> value.getString().strip()).toList();
    }

    ItemStack intrinsicCopy(ItemStack stack, AuctionInteractionProfile profile) {
        ItemStack copy = stack.copyWithCount(1);
        LoreComponent lore = copy.get(DataComponentTypes.LORE);
        if (lore == null) {
            return copy;
        }
        List<Text> intrinsic = lore.lines().stream()
                .filter(line -> !ignored(line.getString().strip(), profile))
                .toList();
        if (intrinsic.isEmpty()) {
            copy.remove(DataComponentTypes.LORE);
        } else {
            copy.set(DataComponentTypes.LORE, new LoreComponent(intrinsic));
        }
        return copy;
    }

    private ItemDescriptor descriptor(
            ItemStack stack, AuctionInteractionProfile profile, int depth
    ) {
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();
        Optional<String> customName = Optional.ofNullable(stack.get(DataComponentTypes.CUSTOM_NAME))
                .map(Text::getString);
        List<String> intrinsicLore = visibleLore(stack).stream()
                .filter(line -> !ignored(line, profile)).toList();
        List<ItemDescriptor> contents = depth >= MAXIMUM_CONTAINER_DEPTH
                ? List.of() : contents(stack, profile, depth);
        return new ItemDescriptor(
                Optional.of(itemId), OptionalInt.of(stack.getCount()), customName, intrinsicLore,
                enchantments(stack), trim(stack), contents, List.of()
        );
    }

    private List<ItemDescriptor> contents(
            ItemStack stack, AuctionInteractionProfile profile, int depth
    ) {
        ContainerComponent container = stack.get(DataComponentTypes.CONTAINER);
        if (container == null) {
            return List.of();
        }
        return container.streamNonEmpty().limit(256)
                .map(value -> descriptor(value, profile, depth + 1)).toList();
    }

    private static List<ItemEnchantmentDescriptor> enchantments(ItemStack stack) {
        Map<String, Integer> levels = new TreeMap<>();
        addEnchantments(levels, stack.get(DataComponentTypes.ENCHANTMENTS));
        addEnchantments(levels, stack.get(DataComponentTypes.STORED_ENCHANTMENTS));
        List<ItemEnchantmentDescriptor> result = new ArrayList<>();
        levels.forEach((id, level) -> result.add(new ItemEnchantmentDescriptor(id, level)));
        return List.copyOf(result);
    }

    private static void addEnchantments(
            Map<String, Integer> levels, ItemEnchantmentsComponent component
    ) {
        if (component == null) {
            return;
        }
        component.getEnchantmentEntries().forEach(entry -> levels.merge(
                entry.getKey().getIdAsString(), entry.getIntValue(), Math::max
        ));
    }

    private static Optional<ArmorTrimDescriptor> trim(ItemStack stack) {
        ArmorTrim value = stack.get(DataComponentTypes.TRIM);
        if (value == null) {
            return Optional.empty();
        }
        return Optional.of(new ArmorTrimDescriptor(
                Optional.of(value.material().getIdAsString()),
                Optional.of(value.pattern().getIdAsString()),
                List.of()
        ));
    }

    private static boolean ignored(String line, AuctionInteractionProfile profile) {
        if (!profile.listingKeyLorePrefix().isBlank()
                && line.startsWith(profile.listingKeyLorePrefix())) {
            return true;
        }
        if (line.startsWith(profile.priceLorePrefix())
                || line.startsWith(profile.sellerLorePrefix())) {
            return true;
        }
        return profile.ignoredLorePrefixes().stream().anyMatch(line::startsWith);
    }
}
