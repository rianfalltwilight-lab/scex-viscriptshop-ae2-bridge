package cn.scex.viscriptshopae2;

import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.common.IOUtilities;

@EventBusSubscriber(modid = ScexViScriptShopAe2.MOD_ID)
public final class JournalRecoveryHandler {
    private JournalRecoveryHandler() {}

    @SubscribeEvent
    public static void serverStarted(ServerStartedEvent event) {
        if (!TradeJournal.recoverAll(event.getServer())) {
            ScexViScriptShopAe2.LOGGER.warn("Incomplete SCEX shop transactions remain pending recovery");
        }
    }

    @SubscribeEvent
    public static void playerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) TradeJournal.recoverAll(player.server);
    }

    @SubscribeEvent
    public static void serverStopped(ServerStoppedEvent event) {
        // Minecraft has completed its normal player/world save before this lifecycle event.
        TradeJournal.checkpointApplied(event.getServer());
    }

    @SubscribeEvent
    public static void levelSaved(LevelEvent.Save event) {
        if (event.getLevel() instanceof net.minecraft.server.level.ServerLevel level
                && level.dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            // LevelEvent.Save is posted after chunk/entity save submission. Wait at the existing
            // autosave boundary, then retire only journals already applied in this JVM.
            IOUtilities.waitUntilIOWorkerComplete();
            TradeJournal.checkpointApplied(level.getServer());
        }
    }
}
