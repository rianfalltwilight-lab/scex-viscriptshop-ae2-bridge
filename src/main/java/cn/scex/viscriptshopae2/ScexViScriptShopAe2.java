package cn.scex.viscriptshopae2;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import org.slf4j.Logger;

@Mod(ScexViScriptShopAe2.MOD_ID)
public final class ScexViScriptShopAe2 {
    public static final String MOD_ID = "scex_viscriptshop_ae2";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ScexViScriptShopAe2(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, BridgeConfig.SPEC);
    }
}
