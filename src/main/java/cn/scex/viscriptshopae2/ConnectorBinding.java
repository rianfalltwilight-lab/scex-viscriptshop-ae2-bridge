package cn.scex.viscriptshopae2;

import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.storage.MEStorage;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

public record ConnectorBinding(UUID owner, ResourceKey<Level> dimension, BlockPos pos) {
    public static Optional<ConnectorBinding> find(ServerPlayer player) {
        return ConnectorLinkData.get(player.server).find(player.getUUID())
                .map(pos -> new ConnectorBinding(player.getUUID(), pos.dimension(), pos.pos()));
    }

    static ConnectorBinding of(UUID owner, GlobalPos pos) {
        return new ConnectorBinding(owner, pos.dimension(), pos.pos());
    }

    public Optional<ResolvedConnector> resolve(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunkAt(pos)
                || !(level.getBlockEntity(pos) instanceof MeShopConnectorBlockEntity connector)) {
            return Optional.empty();
        }
        IGridNode node = connector.getMainNode().getNode();
        if (node == null || !connector.getMainNode().isReady() || !node.isActive() || !node.isOnline()
                || !owner.equals(node.getOwningPlayerProfileId())) return Optional.empty();
        IGrid grid = node.getGrid();
        if (grid == null) return Optional.empty();
        MEStorage storage = grid.getStorageService().getInventory();
        return Optional.of(new ResolvedConnector(level, connector, node, grid, storage));
    }

    public String unavailableReason(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) return "dimension missing";
        if (!level.hasChunkAt(pos)) return "connector chunk unloaded";
        var blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof MeShopConnectorBlockEntity connector)) {
            return "connector block entity missing (found "
                    + (blockEntity == null ? "none" : blockEntity.getType()) + ")";
        }
        IGridNode node = connector.getMainNode().getNode();
        if (node == null) return "grid node missing";
        if (!connector.getMainNode().isReady()) return "grid node not ready";
        if (!node.isActive()) return "grid node inactive";
        if (!node.isOnline()) return "grid node offline";
        if (!owner.equals(node.getOwningPlayerProfileId())) {
            return "owner mismatch (node=" + node.getOwningPlayerProfileId() + ")";
        }
        if (node.getGrid() == null) return "grid missing";
        return "online";
    }

    public record ResolvedConnector(ServerLevel level, MeShopConnectorBlockEntity connector,
                                    IGridNode node, IGrid grid, MEStorage storage) {}
}
