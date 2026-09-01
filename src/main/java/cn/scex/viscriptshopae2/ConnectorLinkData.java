package cn.scex.viscriptshopae2;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

final class ConnectorLinkData extends SavedData {
    private static final String FILE = "scex_viscriptshop_ae2_connectors";
    private final Map<UUID, GlobalPos> links = new LinkedHashMap<>();

    static ConnectorLinkData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(ConnectorLinkData::new, ConnectorLinkData::load), FILE);
    }

    Optional<GlobalPos> find(UUID player) {
        return Optional.ofNullable(links.get(player));
    }

    void bind(UUID player, GlobalPos pos) {
        links.put(player, pos);
        setDirty();
    }

    void unbind(UUID player, GlobalPos expected) {
        if (links.remove(player, expected)) setDirty();
    }

    private static ConnectorLinkData load(CompoundTag root, HolderLookup.Provider registries) {
        ConnectorLinkData data = new ConnectorLinkData();
        ListTag entries = root.getList("links", Tag.TAG_COMPOUND);
        for (int index = 0; index < entries.size(); index++) {
            CompoundTag entry = entries.getCompound(index);
            if (!entry.hasUUID("player") || entry.getString("dimension").isBlank()
                    || !entry.contains("pos", Tag.TAG_LONG)) continue;
            try {
                ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                        ResourceLocation.parse(entry.getString("dimension")));
                data.links.put(entry.getUUID("player"), GlobalPos.of(dimension, BlockPos.of(entry.getLong("pos"))));
            } catch (RuntimeException malformed) {
                ScexViScriptShopAe2.LOGGER.warn("Ignoring malformed saved ME shop connector link", malformed);
            }
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag root, HolderLookup.Provider registries) {
        ListTag entries = new ListTag();
        links.forEach((player, pos) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", player);
            entry.putString("dimension", pos.dimension().location().toString());
            entry.putLong("pos", pos.pos().asLong());
            entries.add(entry);
        });
        root.put("links", entries);
        return root;
    }
}
