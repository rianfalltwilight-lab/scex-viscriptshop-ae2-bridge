package cn.scex.viscriptshopae2.gametest;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.storage.MEStorage;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import cn.scex.viscriptshopae2.MeShopConnectorBlockEntity;
import cn.scex.viscriptshopae2.ModContent;
import com.lowdragmc.lowdraglib2.syncdata.rpc.RPCSender;
import com.mojang.authlib.GameProfile;
import com.viscriptshop.ViscriptShop;
import com.viscriptshop.event.neoforge.ShopServerEvent;
import com.viscriptshop.gui.data.AggregatedResources;
import com.viscriptshop.gui.data.CategoryInfo;
import com.viscriptshop.gui.data.MerchantInfo;
import com.viscriptshop.gui.data.ShopInfo;
import com.viscriptshop.gui.data.ShopSavedData;
import com.viscriptshop.network.c2s.BuyMerchantPayload;
import com.viscriptshop.util.ViScriptShopServerUtil;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.LockSupport;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Probe-only test. The gametest source set is excluded from the formal release JAR. */
@GameTestHolder("scex_viscriptshop_ae2_crash_probe")
@PrefixGameTestTemplate(false)
public final class CrashRecoveryGameTests {
    private static final BlockPos CONNECTOR = new BlockPos(2, 2, 2);
    private static final BlockPos DRIVE = new BlockPos(3, 2, 2);
    private static final BlockPos POWER = new BlockPos(4, 2, 2);
    private CrashRecoveryGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void externallyKilledWalBoundary(GameTestHelper helper) {
        String killPhase = System.getProperty("scex.walKillPhase", "").trim();
        String verifyPhase = System.getProperty("scex.walVerifyPhase", "").trim();
        if (!killPhase.isEmpty() && !verifyPhase.isEmpty()) {
            throw new AssertionError("Choose either walKillPhase or walVerifyPhase");
        }
        if (!killPhase.isEmpty()) {
            CrashRig rig = setupRig(helper, killPhase);
            helper.runAfterDelay(40, () -> createKillBoundary(rig, killPhase));
            return;
        }
        if (!verifyPhase.isEmpty()) {
            CompoundTag journal = JournalProbeAccessor.latestJournal(helper.getLevel().getServer()).data();
            BlockPos connectorPos = BlockPos.of(journal.getLong("connector"));
            setFixtureForced(helper, connectorPos, true);
            helper.getLevel().getChunkAt(connectorPos);
            helper.getLevel().getChunkAt(connectorPos.relative(Direction.EAST, 2));
            helper.runAfterDelay(80, () -> verifyRestart(helper, verifyPhase));
            return;
        }
        helper.succeed();
    }

    private static void createKillBoundary(CrashRig rig, String phase) {
        if (!List.of("payment_extracted", "inventory_applied", "goods_inserted", "committed")
                .contains(phase)) throw new AssertionError("Unknown hard-kill phase " + phase);
        IActionSource source = IActionSource.ofPlayer(rig.player());
        AEItemKey diamond = AEItemKey.of(Items.DIAMOND);
        AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
        if (rig.storage().insert(iron, 1, Actionable.MODULATE, source) != 1) {
            throw new AssertionError("Crash probe could not seed one ME iron payment");
        }
        rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT));
        rig.player().server.saveEverything(true, true, true);
        if (rig.storage().extract(iron, 1, Actionable.SIMULATE, source) != 1) {
            throw new AssertionError("Crash probe baseline save lost the ME iron payment");
        }

        if (phase.equals("committed")) {
            invokeAuthoritativeBuy(rig.player(), rig.shop());
        } else {
            var binding = cn.scex.viscriptshopae2.ConnectorBinding.find(rig.player()).orElseThrow();
            JournalProbeAccessor.prepare(rig.player(), rig.shop(), binding, makeEvent(rig), rig.storage(),
                    List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_INGOT)));
            rig.storage().extract(iron, 1, Actionable.MODULATE, source);
            if (!phase.equals("payment_extracted")) {
                JournalProbeAccessor.applyLatestPostInventory(rig.player().server, rig.player(), rig.shop());
            }
            if (phase.equals("goods_inserted")) {
                rig.storage().insert(diamond, 1, Actionable.MODULATE, source);
            }
        }
        JournalProbeAccessor.writeProbeSentinel(rig.player().server, "scex.walSentinel", phase,
                "boundary_fsynced_waiting_for_external_kill");
        while (true) LockSupport.parkNanos(1_000_000_000L);
    }

    private static void verifyRestart(GameTestHelper helper, String phase) {
        var probe = JournalProbeAccessor.latestJournal(helper.getLevel().getServer());
        CompoundTag journal = probe.data();
        helper.assertTrue(journal.getString("shop").equals("hard-kill-" + phase),
                "restart must inspect the journal from the requested kill phase");
        ConnectorLinkAccessor.setBinding(helper.getLevel().getServer(), journal);
        BlockPos connectorPos = BlockPos.of(journal.getLong("connector"));
        helper.getLevel().getChunkAt(connectorPos);
        helper.getLevel().getChunkAt(connectorPos.relative(Direction.EAST, 2));

        UUID playerId = journal.getUUID("player");
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(playerId, "scex-hard-kill-" + phase));
        player.setGameMode(GameType.SURVIVAL);
        BlockPos playerPos = helper.absolutePos(CONNECTOR).relative(Direction.NORTH);
        player.setPos(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5);
        var slots = journal.getList("slots", Tag.TAG_COMPOUND);
        for (int index = 0; index < slots.size(); index++) {
            CompoundTag transition = slots.getCompound(index);
            player.getInventory().setItem(transition.getInt("slot"), ItemStack.parseOptional(
                    player.server.registryAccess(), transition.getCompound("pre")));
        }
        ViScriptShopServerUtil.setMoney(player, journal.getInt("money"));
        player.totalExperience = journal.getInt("xpTotal");
        player.experienceLevel = journal.getInt("xpLevel");
        player.experienceProgress = journal.getFloat("xpProgress");
        if (ViscriptShop.getShopSavedData() == null) ViscriptShop.setShopSavedData(new ShopSavedData());
        for (int index = 0; index < journal.getList("stocks", Tag.TAG_COMPOUND).size(); index++) {
            CompoundTag stock = journal.getList("stocks", Tag.TAG_COMPOUND).getCompound(index);
            ViScriptShopServerUtil.setMerchantStock(journal.getString("shop"), stock.getString("category"),
                    stock.getString("merchant"), stock.getInt("amount"));
        }
        JournalProbeAccessor.registerPlayer(player);
        var restartBinding = cn.scex.viscriptshopae2.ConnectorBinding.find(player).orElseThrow();
        helper.assertTrue(restartBinding.resolve(player.server).isPresent(),
                "restart fixture must resolve the saved online connector before WAL replay: "
                        + restartBinding.unavailableReason(player.server));

        ItemStack slotBefore = player.getInventory().getItem(slots.getCompound(0).getInt("slot")).copy();
        boolean recovered = JournalProbeAccessor.simulateNewProcessAndTryReplayAll(player.server);
        if (phase.equals("committed")) {
            helper.assertTrue(!recovered, "COMMITTED journal with restart pre-state must fail closed");
            helper.assertTrue(ItemStack.matches(slotBefore,
                            player.getInventory().getItem(slots.getCompound(0).getInt("slot"))),
                    "failed-closed COMMITTED recovery must not rewrite player state");
            helper.assertTrue(JournalProbeAccessor.stateForShop(player.server, journal.getString("shop"))
                            .equals("COMMITTED"),
                    "ambiguous COMMITTED journal must remain for operator diagnosis");
        } else {
            helper.assertTrue(recovered, "unique tail PREPARED journal must recover after external kill");
            helper.assertTrue(JournalProbeAccessor.stateForShop(player.server, journal.getString("shop"))
                            .equals("ROLLED_BACK"),
                    "external PREPARED recovery must fsync ROLLED_BACK before confirmation");
        }
        JournalProbeAccessor.writeProbeSentinel(player.server, "scex.walVerifySentinel", phase,
                recovered ? "restart_recovered" : "restart_failed_closed_as_required");
        setFixtureForced(helper, connectorPos, false);
        helper.succeed();
    }

    private static void setFixtureForced(GameTestHelper helper, BlockPos connectorPos, boolean forced) {
        var first = new net.minecraft.world.level.ChunkPos(connectorPos);
        var last = new net.minecraft.world.level.ChunkPos(connectorPos.relative(Direction.EAST, 2));
        helper.getLevel().setChunkForced(first.x, first.z, forced);
        helper.getLevel().setChunkForced(last.x, last.z, forced);
    }

    private static CrashRig setupRig(GameTestHelper helper, String phase) {
        String shop = "hard-kill-" + phase;
        UUID playerId = UUID.nameUUIDFromBytes(("scex-hard-kill-" + phase).getBytes(StandardCharsets.UTF_8));
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(playerId, "scex-hard-kill-" + phase));
        player.setGameMode(GameType.SURVIVAL);
        helper.setBlock(POWER, AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(DRIVE, AEBlocks.DRIVE.block());
        DriveBlockEntity drive = (DriveBlockEntity) helper.getBlockEntity(DRIVE);
        drive.getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_64K.stack());
        helper.setBlock(CONNECTOR, ModContent.ME_SHOP_CONNECTOR.get());
        MeShopConnectorBlockEntity connector = (MeShopConnectorBlockEntity) helper.getBlockEntity(CONNECTOR);
        BlockPos connectorPos = helper.absolutePos(CONNECTOR);
        ModContent.ME_SHOP_CONNECTOR.get().setPlacedBy(helper.getLevel(), connectorPos,
                helper.getLevel().getBlockState(connectorPos), player,
                new ItemStack(ModContent.ME_SHOP_CONNECTOR_ITEM.get()));

        MerchantInfo merchant = new MerchantInfo();
        merchant.setId("diamond");
        merchant.setItemA(new ItemStack(Items.IRON_INGOT, 2));
        merchant.setItemResult(new ItemStack(Items.DIAMOND));
        merchant.setStock(1);
        merchant.setXp(3);
        CategoryInfo category = new CategoryInfo();
        category.setId("items");
        category.setShopType(CategoryInfo.ShopType.ITEM_FOR_ITEM);
        category.getMerchants().add(merchant);
        ShopInfo info = new ShopInfo();
        info.getCategoryInfos().add(category);
        if (ViscriptShop.getShopSavedData() == null) ViscriptShop.setShopSavedData(new ShopSavedData());
        ViScriptShopServerUtil.setShopInfo(shop, info);
        JournalProbeAccessor.registerPlayer(player);
        return new CrashRig(player, shop, merchant, info, new LazyStorage(connector));
    }

    private static ShopServerEvent.BuyPre makeEvent(CrashRig rig) {
        AggregatedResources cost = new AggregatedResources();
        cost.addItemEntry(rig.merchant().getItemA().copyWithCount(1), 2, rig.merchant().getItemAMatchRule());
        AggregatedResources gain = new AggregatedResources();
        gain.addItem(rig.merchant().getItemResult().copyWithCount(1), 1);
        gain.setTotalXp(3);
        gain.getPurchaseEntries().add(new AggregatedResources.PurchaseEntry("items", "diamond", 1));
        return new ShopServerEvent.BuyPre(rig.player(), rig.info(), cost, gain);
    }

    private static void invokeAuthoritativeBuy(ServerPlayer player, String shop) {
        AggregatedResources request = new AggregatedResources();
        request.getPurchaseEntries().add(new AggregatedResources.PurchaseEntry("items", "diamond", 1));
        BuyMerchantPayload.buyMerchant(RPCSender.ofClient(player), shop, new AggregatedResources(), request);
    }

    private record CrashRig(ServerPlayer player, String shop, MerchantInfo merchant, ShopInfo info,
                            appeng.api.storage.MEStorage storage) {}

    private static final class LazyStorage implements MEStorage {
        private final MeShopConnectorBlockEntity connector;
        private LazyStorage(MeShopConnectorBlockEntity connector) { this.connector = connector; }
        private MEStorage delegate() {
            var grid = connector.getMainNode().getGrid();
            if (grid == null) throw new IllegalStateException("crash connector grid unavailable");
            return grid.getStorageService().getInventory();
        }
        @Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            return delegate().insert(key, amount, mode, source);
        }
        @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return delegate().extract(key, amount, mode, source);
        }
        @Override public void getAvailableStacks(KeyCounter out) { delegate().getAvailableStacks(out); }
        @Override public Component getDescription() { return delegate().getDescription(); }
    }
}
