package cn.scex.viscriptshopae2;

import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacket;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import net.minecraft.server.level.ServerPlayer;

public final class BridgeMoneySync {
    public static final String SYNC_ME_MONEY = "scex_viscriptshop_ae2:sync_me_money";
    private static volatile int clientMeMoney;

    private BridgeMoneySync() {}

    public static void sync(ServerPlayer player) {
        int visible = 0;
        try {
            var binding = ConnectorBinding.find(player);
            if (binding.isPresent()) {
                var resolved = binding.get().resolve(player.server);
                if (resolved.isPresent() && resolved.get().level().mayInteract(player, binding.get().pos())) {
                    synchronized (resolved.get().grid()) {
                        visible = MeCurrency.visibleValue(resolved.get().storage(), MeCurrency.SCEX_CATALOG);
                    }
                }
            }
        } catch (RuntimeException unavailable) {
            ScexViScriptShopAe2.LOGGER.warn("Cannot read ME currency balance for {}: {}",
                    player.getGameProfile().getName(), unavailable.toString());
        }
        try {
            RPCPacketDistributor.rpcToPlayer(player, SYNC_ME_MONEY, visible);
        } catch (RuntimeException disconnected) {
            ScexViScriptShopAe2.LOGGER.debug("Cannot sync ME currency balance to {}: {}",
                    player.getGameProfile().getName(), disconnected.toString());
        }
    }

    public static int clientMeMoney() {
        return clientMeMoney;
    }

    @RPCPacket(value = SYNC_ME_MONEY, modId = ScexViScriptShopAe2.MOD_ID)
    public static void receive(RPCSender sender, int value) {
        clientMeMoney = Math.max(0, value);
    }
}
