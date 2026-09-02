package cn.scex.viscriptshopae2;

import appeng.api.networking.GridFlags;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IGridNodeListener;
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

    public boolean isVisualActive() {
        IGridNode node = getMainNode().getNode();
        return node != null && getMainNode().isReady() && node.isActive() && node.isOnline();
    }

    @Override
    public void onMainNodeStateChanged(IGridNodeListener.State reason) {
        markForUpdate();
    }
}
