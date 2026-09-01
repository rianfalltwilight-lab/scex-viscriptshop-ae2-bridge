package cn.scex.viscriptshopae2.gametest;

import cn.scex.viscriptshopae2.BridgeConfig;
import java.util.List;
import java.util.ArrayList;
import net.minecraft.core.BlockPos;

final class BridgeConfigAccessor {
    private BridgeConfigAccessor() {}
    static void setBinding(String shop, BlockPos pos) {
        List<? extends String> configured = BridgeConfig.BINDINGS.get();
        List<String> bindings = new ArrayList<>(configured);
        bindings.add(shop + "|minecraft:overworld|" + pos.getX() + "|" + pos.getY() + "|"
                + pos.getZ() + "|north");
        BridgeConfig.BINDINGS.set(bindings);
    }

    static void requireOwner(boolean value) {
        BridgeConfig.REQUIRE_TERMINAL_OWNER.set(value);
    }
}
