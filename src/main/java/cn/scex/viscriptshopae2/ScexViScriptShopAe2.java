package cn.scex.viscriptshopae2;

import appeng.api.AECapabilities;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.capabilities.RegisterCapabilitiesEvent;
import org.slf4j.Logger;

@Mod(ScexViScriptShopAe2.MOD_ID)
public final class ScexViScriptShopAe2 {
    public static final String MOD_ID = "scex_viscriptshop_ae2";
    public static final Logger LOGGER = LogUtils.getLogger();

    public ScexViScriptShopAe2(IEventBus modBus, ModContainer container) {
        ModContent.BLOCKS.register(modBus);
        ModContent.ITEMS.register(modBus);
        ModContent.BLOCK_ENTITIES.register(modBus);
        modBus.addListener(ScexViScriptShopAe2::addCreativeTabContents);
        modBus.addListener(ScexViScriptShopAe2::commonSetup);
        modBus.addListener(ScexViScriptShopAe2::registerCapabilities);
    }

    private static void commonSetup(FMLCommonSetupEvent event) {
        event.enqueueWork(() -> {
            ModContent.ME_SHOP_CONNECTOR.get().setBlockEntity(
                    MeShopConnectorBlockEntity.class, ModContent.ME_SHOP_CONNECTOR_BLOCK_ENTITY.get(), null, null);
            appeng.blockentity.AEBaseBlockEntity.registerBlockEntityItem(
                    ModContent.ME_SHOP_CONNECTOR_BLOCK_ENTITY.get(), ModContent.ME_SHOP_CONNECTOR_ITEM.get());
        });
    }

    private static void registerCapabilities(RegisterCapabilitiesEvent event) {
        event.registerBlockEntity(AECapabilities.IN_WORLD_GRID_NODE_HOST,
                ModContent.ME_SHOP_CONNECTOR_BLOCK_ENTITY.get(), (connector, ignored) -> connector);
    }

    private static void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.FUNCTIONAL_BLOCKS) {
            event.accept(ModContent.ME_SHOP_CONNECTOR_ITEM.get());
        }
    }
}
