package cn.scex.viscriptshopae2;

import appeng.api.stacks.AEItemKey;
import com.viscriptshop.gui.data.AggregatedResources;
import com.viscriptshop.gui.data.ItemMatchRule;
import net.minecraft.server.level.ServerPlayer;

public final class ConnectorInventory {
    private ConnectorInventory() {}

    public static int countVisibleItems(ServerPlayer player, AggregatedResources.ItemEntry requested) {
        var resolved = ConnectorBinding.find(player).flatMap(binding -> binding.resolve(player.server));
        if (resolved.isEmpty()) return 0;
        ItemMatchRule rule = requested.getMatchRule() == null ? new ItemMatchRule() : requested.getMatchRule();
        long count = 0;
        for (var entry : resolved.get().storage().getAvailableStacks()) {
            if (entry.getKey() instanceof AEItemKey key
                    && rule.matches(key.getReadOnlyStack(), requested.getItemStack())) {
                count = Math.min(Integer.MAX_VALUE, count + entry.getLongValue());
            }
        }
        return (int) count;
    }

    public static int saturatingAdd(int inventory, int network) {
        return (int) Math.min(Integer.MAX_VALUE, (long) Math.max(0, inventory) + Math.max(0, network));
    }
}
