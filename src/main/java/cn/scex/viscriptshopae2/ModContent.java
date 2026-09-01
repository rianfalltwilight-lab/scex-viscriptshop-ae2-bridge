package cn.scex.viscriptshopae2;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModContent {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(ScexViScriptShopAe2.MOD_ID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ScexViScriptShopAe2.MOD_ID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ScexViScriptShopAe2.MOD_ID);

    public static final DeferredBlock<MeShopConnectorBlock> ME_SHOP_CONNECTOR = BLOCKS.register(
            "me_shop_connector", MeShopConnectorBlock::new);
    public static final DeferredItem<BlockItem> ME_SHOP_CONNECTOR_ITEM = ITEMS.registerSimpleBlockItem(
            "me_shop_connector", ME_SHOP_CONNECTOR, new Item.Properties());
    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<MeShopConnectorBlockEntity>>
            ME_SHOP_CONNECTOR_BLOCK_ENTITY = BLOCK_ENTITIES.register("me_shop_connector",
            () -> BlockEntityType.Builder.of(MeShopConnectorBlockEntity::new, ME_SHOP_CONNECTOR.get()).build(null));

    private ModContent() {}
}
