package cn.scex.viscriptshopae2.mixin;

import cn.scex.viscriptshopae2.ConnectorInventory;
import com.viscriptshop.gui.data.AggregatedResources;
import com.viscriptshop.network.c2s.GetItemCountC2SPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(value = GetItemCountC2SPayload.class, remap = false)
public abstract class GetItemCountC2SPayloadMixin {
    @Redirect(method = "getItemCount", at = @At(value = "INVOKE",
            target = "Lcom/viscriptshop/gui/data/AggregatedResources$ItemEntry;getItemForPlayerCount(Lnet/minecraft/server/level/ServerPlayer;)I"),
            remap = false)
    private static int scex$includeConnectedMeInventory(AggregatedResources.ItemEntry entry, ServerPlayer player) {
        return ConnectorInventory.saturatingAdd(entry.getItemForPlayerCount(player),
                ConnectorInventory.countVisibleItems(player, entry));
    }
}
