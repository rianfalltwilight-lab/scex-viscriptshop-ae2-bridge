package cn.scex.viscriptshopae2.gametest;

import java.lang.reflect.Method;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

final class ConnectorLinkAccessor {
    private ConnectorLinkAccessor() {}

    static void setBinding(ServerPlayer player, BlockPos pos) {
        bind(player.server, player.getUUID(), GlobalPos.of(player.level().dimension(), pos));
    }

    static void requireOwner(boolean value) {
        // Connector ownership is intrinsic and cannot be disabled.
    }

    static void setBinding(MinecraftServer server, CompoundTag journal) {
        var dimension = net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION,
                net.minecraft.resources.ResourceLocation.parse(journal.getString("dimension")));
        bind(server, journal.getUUID("player"),
                GlobalPos.of(dimension, BlockPos.of(journal.getLong("connector"))));
    }

    private static void bind(MinecraftServer server, java.util.UUID player, GlobalPos pos) {
        try {
            Class<?> dataClass = Class.forName("cn.scex.viscriptshopae2.ConnectorLinkData");
            Method get = dataClass.getDeclaredMethod("get", MinecraftServer.class);
            get.setAccessible(true);
            Object data = get.invoke(null, server);
            Method bind = dataClass.getDeclaredMethod("bind", java.util.UUID.class, GlobalPos.class);
            bind.setAccessible(true);
            bind.invoke(data, player, pos);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot bind test ME shop connector", failure);
        }
    }
}
