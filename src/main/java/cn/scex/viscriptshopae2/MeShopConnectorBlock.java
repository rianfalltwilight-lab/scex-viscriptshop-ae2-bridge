package cn.scex.viscriptshopae2;

import appeng.block.AEBaseBlock;
import appeng.block.AEBaseEntityBlock;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;

public final class MeShopConnectorBlock extends AEBaseEntityBlock<MeShopConnectorBlockEntity> {
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");

    public MeShopConnectorBlock() {
        super(AEBaseBlock.metalProps().strength(3.5F));
        registerDefaultState(defaultBlockState().setValue(ACTIVE, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(ACTIVE);
    }

    @Override
    protected BlockState updateBlockStateFromBlockEntity(BlockState state,
                                                          MeShopConnectorBlockEntity connector) {
        return super.updateBlockStateFromBlockEntity(state, connector)
                .setValue(ACTIVE, connector.isVisualActive());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (level instanceof ServerLevel serverLevel && placer instanceof ServerPlayer player) {
            ConnectorLinkData.get(serverLevel.getServer()).bind(player.getUUID(), GlobalPos.of(level.dimension(), pos));
            player.displayClientMessage(Component.translatable("scex_viscriptshop_ae2.connector.bound"), true);
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player,
                                                BlockHitResult hit) {
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            ConnectorBinding.find(serverPlayer).flatMap(binding -> binding.resolve(serverPlayer.server))
                    .filter(resolved -> resolved.connector().getBlockPos().equals(pos)
                            && resolved.level().dimension().equals(level.dimension()))
                    .ifPresentOrElse(
                            resolved -> serverPlayer.displayClientMessage(
                                    Component.translatable("scex_viscriptshop_ae2.connector.online"), true),
                            () -> serverPlayer.displayClientMessage(
                                    Component.translatable("scex_viscriptshop_ae2.connector.offline"), true));
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel
                && level.getBlockEntity(pos) instanceof MeShopConnectorBlockEntity connector) {
            UUID owner = connector.getMainNode().getNode() == null ? null
                    : connector.getMainNode().getNode().getOwningPlayerProfileId();
            if (owner != null) {
                ConnectorLinkData.get(serverLevel.getServer()).unbind(owner, GlobalPos.of(level.dimension(), pos));
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
