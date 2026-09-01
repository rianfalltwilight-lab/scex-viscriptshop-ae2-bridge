package cn.scex.viscriptshopae2;

import java.util.List;
import net.neoforged.neoforge.common.ModConfigSpec;

public final class BridgeConfig {
    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> BINDINGS;
    public static final ModConfigSpec.BooleanValue REQUIRE_TERMINAL_OWNER;

    static {
        var b = new ModConfigSpec.Builder();
        BINDINGS = b.comment("shop|dimension|x|y|z|side, for example main|minecraft:overworld|10|64|20|north")
                .defineListAllowEmpty("bindings", List.of(), BridgeConfig::validBinding);
        REQUIRE_TERMINAL_OWNER = b.comment("Require the terminal part node to be owned by the trading player.")
                .define("requireTerminalOwner", true);
        SPEC = b.build();
    }

    private static boolean validBinding(Object value) {
        if (!(value instanceof String s)) return false;
        String[] p = s.split("\\|", -1);
        if (p.length != 6 || p[0].isBlank() || p[1].isBlank()) return false;
        try {
            Integer.parseInt(p[2]); Integer.parseInt(p[3]); Integer.parseInt(p[4]);
            return net.minecraft.core.Direction.byName(p[5]) != null;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }
}
