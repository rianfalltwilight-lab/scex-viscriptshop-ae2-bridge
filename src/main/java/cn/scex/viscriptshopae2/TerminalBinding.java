package cn.scex.viscriptshopae2;

import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.storage.ITerminalHost;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

public record TerminalBinding(String shop, ResourceKey<Level> dimension, BlockPos pos, Direction side) {
    public static Optional<TerminalBinding> find(String shop) {
        for (String raw : BridgeConfig.BINDINGS.get()) {
            String[] p = raw.split("\\|", -1);
            if (!p[0].equals(shop)) continue;
            try {
                var dim = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        ResourceLocation.parse(p[1]));
                return Optional.of(new TerminalBinding(shop, dim,
                        new BlockPos(Integer.parseInt(p[2]), Integer.parseInt(p[3]), Integer.parseInt(p[4])),
                        Direction.byName(p[5])));
            } catch (RuntimeException ignored) {
                return Optional.empty();
            }
        }
        return Optional.empty();
    }

    public Optional<ResolvedTerminal> resolve(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null || !level.hasChunkAt(pos)) return Optional.empty();
        if (!(level.getBlockEntity(pos) instanceof IPartHost host)) return Optional.empty();
        IPart part = host.getPart(side);
        if (!(part instanceof ITerminalHost terminal) || part.getGridNode() == null
                || !part.getGridNode().isActive() || !terminal.getLinkStatus().connected()) return Optional.empty();
        return Optional.of(new ResolvedTerminal(level, host, part, terminal));
    }

    public record ResolvedTerminal(ServerLevel level, IPartHost host, IPart part, ITerminalHost terminal) {}
}
