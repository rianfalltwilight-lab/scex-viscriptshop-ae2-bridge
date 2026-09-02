package cn.scex.viscriptshopae2.mixin;

import cn.scex.viscriptshopae2.BridgeMoneySync;
import cn.scex.viscriptshopae2.ConnectorInventory;
import com.viscriptshop.util.ViScriptShopClientUtil;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ViScriptShopClientUtil.class, remap = false)
public abstract class ViScriptShopClientUtilMixin {
    @Inject(method = "getMoney", at = @At("RETURN"), cancellable = true, remap = false)
    private static void scex$includeMeCurrency(LocalPlayer player, CallbackInfoReturnable<Integer> cir) {
        cir.setReturnValue(ConnectorInventory.saturatingAdd(cir.getReturnValue(), BridgeMoneySync.clientMeMoney()));
    }
}
