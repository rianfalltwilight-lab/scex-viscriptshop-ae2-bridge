package cn.scex.viscriptshopae2.mixin;

import cn.scex.viscriptshopae2.BridgeMoneySync;
import cn.scex.viscriptshopae2.ConnectorInventory;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import com.viscriptshop.gui.data.CategoryInfo;
import com.viscriptshop.gui.data.AggregatedResources;
import com.viscriptshop.network.c2s.GetItemCountC2SPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = GetItemCountC2SPayload.class, remap = false)
public abstract class GetItemCountC2SPayloadMixin {
    @Redirect(method = "getItemCount", at = @At(value = "INVOKE",
            target = "Lcom/viscriptshop/gui/data/AggregatedResources$ItemEntry;getItemForPlayerCount(Lnet/minecraft/server/level/ServerPlayer;)I"),
            remap = false)
    private static int scex$includeConnectedMeInventory(AggregatedResources.ItemEntry entry, ServerPlayer player) {
        return ConnectorInventory.saturatingAdd(entry.getItemForPlayerCount(player),
                ConnectorInventory.countVisibleItems(player, entry));
    }

    @Inject(method = "getItemCount", at = @At("RETURN"), remap = false)
    private static void scex$refreshMeCurrency(RPCSender sender, CategoryInfo category, CallbackInfo ci) {
        ServerPlayer player = sender.asPlayer();
        if (player != null) BridgeMoneySync.sync(player);
    }
}
