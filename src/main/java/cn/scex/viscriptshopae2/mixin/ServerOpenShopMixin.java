package cn.scex.viscriptshopae2.mixin;

import cn.scex.viscriptshopae2.BridgeMoneySync;
import com.viscriptshop.util.ViScriptShopServerUtil;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = ViScriptShopServerUtil.class, remap = false)
public abstract class ServerOpenShopMixin {
    @Inject(method = "serverOpenShop(Lnet/minecraft/server/level/ServerPlayer;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V",
            at = @At("HEAD"), remap = false)
    private static void scex$syncMeCurrency(ServerPlayer player, String shopLocation, String categoryId,
                                            String merchantId, CallbackInfo ci) {
        BridgeMoneySync.sync(player);
    }
}
