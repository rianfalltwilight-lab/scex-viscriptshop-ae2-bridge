package cn.scex.viscriptshopae2;

import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

final class MeCurrency {
    static final CurrencyCatalog SCEX_CATALOG = stack -> valueOf(BuiltInRegistries.ITEM.getKey(stack.getItem()));

    private MeCurrency() {}

    static int valueOf(ResourceLocation itemId) {
        return switch (itemId.toString()) {
            case "scex:coin_1" -> 1;
            case "scex:coin_2" -> 5;
            case "scex:coin_3" -> 10;
            default -> 0;
        };
    }

    static int visibleValue(MEStorage storage, CurrencyCatalog catalog) {
        return visibleValue(storage, catalog, Map.of());
    }

    static int visibleValue(MEStorage storage, CurrencyCatalog catalog, Map<AEItemKey, Long> reserved) {
        long total = 0;
        var available = storage.getAvailableStacks();
        for (var entry : available) {
            if (!(entry.getKey() instanceof AEItemKey key)) continue;
            int unitValue = catalog.value(key.getReadOnlyStack());
            if (unitValue <= 0) continue;
            long count = Math.max(0, entry.getLongValue() - reserved.getOrDefault(key, 0L));
            total = saturatingAdd(total, saturatingMultiply(count, unitValue));
            if (total >= Integer.MAX_VALUE) return Integer.MAX_VALUE;
        }
        return (int) total;
    }

    static Plan plan(MEStorage storage, int cost, int moneyBefore, int moneyGain,
                     Map<AEItemKey, Long> reserved, CurrencyCatalog catalog) {
        if (cost < 0 || moneyBefore < 0 || moneyGain < 0) throw new IllegalArgumentException("Negative money state");
        int digitalDebit = Math.min(cost, moneyBefore);
        long deficit = (long) cost - digitalDebit;
        if (deficit == 0) {
            return new Plan(List.of(), Math.addExact(moneyBefore - digitalDebit, moneyGain), 0);
        }

        Map<Integer, List<AvailableKey>> keysByValue = new LinkedHashMap<>();
        Map<Integer, Long> countsByValue = new LinkedHashMap<>();
        var available = storage.getAvailableStacks();
        for (var entry : available) {
            if (!(entry.getKey() instanceof AEItemKey key)) continue;
            int unitValue = catalog.value(key.getReadOnlyStack());
            if (unitValue <= 0) continue;
            long count = Math.max(0, entry.getLongValue() - reserved.getOrDefault(key, 0L));
            if (count == 0) continue;
            keysByValue.computeIfAbsent(unitValue, ignored -> new ArrayList<>()).add(new AvailableKey(key, count));
            countsByValue.merge(unitValue, count, MeCurrency::saturatingAdd);
        }
        if (countsByValue.isEmpty()) return null;

        List<Integer> denominations = countsByValue.keySet().stream()
                .sorted(Comparator.reverseOrder()).toList();
        int maxDenomination = denominations.getFirst();
        Map<Integer, Long> selected = null;
        long selectedValue = 0;
        for (long target = deficit; target <= deficit + maxDenomination - 1L; target++) {
            long remaining = target;
            Map<Integer, Long> attempt = new LinkedHashMap<>();
            for (int denomination : denominations) {
                long take = Math.min(countsByValue.get(denomination), remaining / denomination);
                if (take > 0) {
                    attempt.put(denomination, take);
                    remaining -= take * denomination;
                }
            }
            if (remaining == 0) {
                selected = attempt;
                selectedValue = target;
                break;
            }
        }
        if (selected == null) return null;

        List<Debit> debits = new ArrayList<>();
        for (var selection : selected.entrySet()) {
            long remaining = selection.getValue();
            for (AvailableKey availableKey : keysByValue.get(selection.getKey())) {
                long take = Math.min(remaining, availableKey.count());
                if (take > 0) {
                    debits.add(new Debit(availableKey.key(), take));
                    remaining -= take;
                }
                if (remaining == 0) break;
            }
            if (remaining != 0) throw new IllegalStateException("Currency selection exceeded ME snapshot");
        }
        long change = selectedValue - deficit;
        int moneyAfter = Math.addExact(Math.addExact(moneyBefore - digitalDebit, Math.toIntExact(change)), moneyGain);
        return new Plan(List.copyOf(debits), moneyAfter, selectedValue);
    }

    private static long saturatingMultiply(long count, int value) {
        if (count <= 0 || value <= 0) return 0;
        if (count > Long.MAX_VALUE / value) return Long.MAX_VALUE;
        return count * value;
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    record Debit(AEItemKey key, long amount) {}
    record Plan(List<Debit> debits, int moneyAfter, long physicalValueDebited) {}
    private record AvailableKey(AEItemKey key, long count) {}
}
