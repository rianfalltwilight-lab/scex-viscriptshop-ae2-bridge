package cn.scex.viscriptshopae2.mixin;

import cn.scex.viscriptshopae2.BridgeContext;
import cn.scex.viscriptshopae2.BridgeMoneySync;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import com.viscriptshop.gui.data.AggregatedResources;
import com.viscriptshop.network.c2s.BuyMerchantPayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = BuyMerchantPayload.class, remap = false)
public abstract class BuyMerchantPayloadMixin {
    @Inject(method = "buyMerchant", at = @At("HEAD"), remap = false)
    private static void scex$rememberShop(RPCSender sender, String shopLocation, AggregatedResources cost,
                                          AggregatedResources gain, CallbackInfo ci) {
        BridgeContext.enter(shopLocation);
    }

    @Inject(method = "buyMerchant", at = @At("RETURN"), remap = false)
    private static void scex$forgetShop(RPCSender sender, String shopLocation, AggregatedResources cost,
                                        AggregatedResources gain, CallbackInfo ci) {
        try {
            var player = sender.asPlayer();
            if (player != null) BridgeMoneySync.sync(player);
        } finally {
            BridgeContext.exit();
        }
    }
}
