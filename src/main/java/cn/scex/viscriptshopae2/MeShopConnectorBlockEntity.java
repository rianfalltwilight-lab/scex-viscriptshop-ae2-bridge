package cn.scex.viscriptshopae2;

import appeng.api.networking.GridFlags;
import appeng.blockentity.grid.AENetworkedBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

public final class MeShopConnectorBlockEntity extends AENetworkedBlockEntity {
    public MeShopConnectorBlockEntity(BlockPos pos, BlockState state) {
        super(ModContent.ME_SHOP_CONNECTOR_BLOCK_ENTITY.get(), pos, state);
        getMainNode()
                .setFlags(GridFlags.REQUIRE_CHANNEL)
                .setIdlePowerUsage(1.0)
                .setVisualRepresentation(ModContent.ME_SHOP_CONNECTOR_ITEM.get());
    }
}
