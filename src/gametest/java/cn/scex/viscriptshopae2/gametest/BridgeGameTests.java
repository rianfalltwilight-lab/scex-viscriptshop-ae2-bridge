package cn.scex.viscriptshopae2.gametest;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.parts.PartHelper;
import appeng.api.stacks.AEItemKey;
import appeng.api.stacks.AEKey;
import appeng.api.stacks.KeyCounter;
import appeng.api.util.AEColor;
import appeng.blockentity.storage.DriveBlockEntity;
import appeng.core.definitions.AEBlocks;
import appeng.core.definitions.AEItems;
import appeng.core.definitions.AEParts;
import appeng.parts.reporting.ItemTerminalPart;
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
import java.util.List;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.GameType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("scex_viscriptshop_ae2")
@PrefixGameTestTemplate(false)
public final class BridgeGameTests {
    private static final BlockPos TERMINAL = new BlockPos(2, 2, 2);
    private static final BlockPos DRIVE = new BlockPos(3, 2, 2);
    private static final BlockPos POWER = new BlockPos(4, 2, 2);
    private BridgeGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void realMultipartAuthoritativePathConservesItems(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "conservation", 1);
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(rig.terminal().getLinkStatus().connected(), "real AE2 multipart terminal must be online");
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1, Actionable.MODULATE,
                    IActionSource.ofPlayer(rig.player()));
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            int xpBefore = rig.player().totalExperience;
            int moneyBefore = ViScriptShopServerUtil.getMoney(rig.player());
            Counters counters = new Counters();
            NeoForge.EVENT_BUS.register(counters);
            invokeAuthoritativeBuy(rig.player(), rig.shop());
            NeoForge.EVENT_BUS.unregister(counters);

            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) == 0, "payment must leave player inventory");
            helper.assertTrue(count(rig.player(), Items.DIAMOND) == 1, "goods must enter player inventory");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(Items.IRON_INGOT), 2, Actionable.SIMULATE,
                    IActionSource.ofPlayer(rig.player())) == 2, "ME must receive exact payment");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(Items.DIAMOND), 1, Actionable.SIMULATE,
                    IActionSource.ofPlayer(rig.player())) == 0, "ME goods must be extracted exactly once");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 0, "shop stock must decrement once");
            helper.assertTrue(ViScriptShopServerUtil.getMoney(rig.player()) == moneyBefore, "item trade money invariant");
            helper.assertTrue(rig.player().totalExperience == xpBefore + 3, "XP gain must commit once");
            helper.assertTrue(counters.success == 1 && counters.fail == 0, "exactly one success event expected");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void insufficientPaymentFailsWithoutMutation(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "insufficient", 1);
        helper.runAfterDelay(30, () -> {
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1, Actionable.MODULATE,
                    IActionSource.ofPlayer(rig.player()));
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 1));
            Counters counters = new Counters(); NeoForge.EVENT_BUS.register(counters);
            invokeAuthoritativeBuy(rig.player(), rig.shop()); NeoForge.EVENT_BUS.unregister(counters);
            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) == 1, "failed payment must stay in inventory");
            helper.assertTrue(count(rig.player(), Items.DIAMOND) == 0, "failed trade must not grant goods");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(Items.DIAMOND), 1, Actionable.SIMULATE,
                    IActionSource.ofPlayer(rig.player())) == 1, "failed trade must preserve ME goods");
            helper.assertTrue(counters.success == 0 && counters.fail == 1, "exactly one fail event expected");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void fullCellRejectsPaymentWithoutMutation(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "capacity", 1);
        helper.runAfterDelay(30, () -> {
            IActionSource source = IActionSource.ofPlayer(rig.player());
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1, Actionable.MODULATE, source);
            rig.storage().insert(AEItemKey.of(Items.COBBLESTONE), Long.MAX_VALUE, Actionable.MODULATE, source);
            helper.assertTrue(rig.storage().insert(AEItemKey.of(Items.IRON_INGOT), 2,
                    Actionable.SIMULATE, source) < 2, "fixture must have insufficient item-cell capacity");
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            Counters counters = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) == 2, "capacity failure preserves payment");
            helper.assertTrue(count(rig.player(), Items.DIAMOND) == 0, "capacity failure grants no goods");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(Items.DIAMOND), 1, Actionable.SIMULATE, source) == 1,
                    "capacity failure preserves ME goods");
            helper.assertTrue(counters.success == 0 && counters.fail == 1, "capacity failure emits BuyFail only");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void disconnectBetweenSimulationAndCommitConservesState(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "disconnect", 1);
        helper.runAfterDelay(30, () -> {
            IActionSource source = IActionSource.ofPlayer(rig.player());
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1, Actionable.MODULATE, source);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            helper.setBlock(DRIVE, Blocks.AIR);
            Counters counters = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) == 2, "disconnect preserves payment");
            helper.assertTrue(count(rig.player(), Items.DIAMOND) == 0, "disconnect grants no goods");
            helper.assertTrue(counters.success == 0 && counters.fail == 1, "disconnect emits BuyFail only");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void componentMatchingUsesActualPaidVariant(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "components", 1);
        ItemStack namedPrice = new ItemStack(Items.IRON_INGOT, 2);
        namedPrice.set(DataComponents.CUSTOM_NAME, Component.literal("SCEX token"));
        rig.merchant().setItemA(namedPrice);
        helper.runAfterDelay(30, () -> {
            IActionSource source = IActionSource.ofPlayer(rig.player());
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1, Actionable.MODULATE, source);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            Counters first = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(first.fail == 1 && count(rig.player(), Items.DIAMOND) == 0,
                    "wrong components must not pay");
            rig.player().getInventory().clearContent();
            rig.player().getInventory().add(namedPrice.copy());
            Counters second = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(second.success == 1 && count(rig.player(), Items.DIAMOND) == 1,
                    "matching components must trade");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(namedPrice), 2, Actionable.SIMULATE, source) == 2,
                    "ME receives the exact component-bearing variant");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void twoPlayersAndDuplicateClickConsumeLastStockOnce(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "laststock", 1);
        ServerPlayer second = makePlayer(helper, "laststock-second");
        BridgeConfigAccessor.requireOwner(false);
        helper.runAfterDelay(30, () -> {
            IActionSource source = IActionSource.ofPlayer(rig.player());
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1, Actionable.MODULATE, source);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 4));
            second.getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            Counters counters = new Counters();
            NeoForge.EVENT_BUS.register(counters);
            try {
                invokeAuthoritativeBuy(rig.player(), rig.shop());
                invokeAuthoritativeBuy(second, rig.shop());
                invokeAuthoritativeBuy(rig.player(), rig.shop());
            } finally {
                NeoForge.EVENT_BUS.unregister(counters);
            }
            helper.assertTrue(count(rig.player(), Items.DIAMOND) + count(second, Items.DIAMOND) == 1,
                    "last stock may be granted exactly once");
            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) + count(second, Items.IRON_INGOT) == 4,
                    "exactly one payment may be consumed");
            helper.assertTrue(counters.success == 1 && counters.fail == 2,
                    "one success and two authoritative BuyFail events expected");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void rollbackAfterGoodsWereExtracted(GameTestHelper helper) {
        runInjectedRollback(helper, "after-goods", FaultPoint.AFTER_GOODS_EXTRACT);
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void rollbackAfterPlayerWasDebited(GameTestHelper helper) {
        runInjectedRollback(helper, "after-debit", FaultPoint.BEFORE_PAYMENT_INSERT);
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void rollbackAfterPartialPaymentInsert(GameTestHelper helper) {
        runInjectedRollback(helper, "partial-insert", FaultPoint.AFTER_PARTIAL_PAYMENT_INSERT);
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void preparedJournalReloadRollsBack(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "reload-prepared", 1);
        helper.runAfterDelay(30, () -> {
            IActionSource source = IActionSource.ofPlayer(rig.player());
            AEItemKey diamond = AEItemKey.of(Items.DIAMOND);
            AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
            rig.storage().insert(diamond, 1, Actionable.MODULATE, source);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            var binding = cn.scex.viscriptshopae2.TerminalBinding.find(rig.shop()).orElseThrow();
            JournalProbeAccessor.prepare(rig.player(), rig.shop(), binding, makeEvent(rig), rig.storage(),
                    List.of(new ItemStack(Items.DIAMOND), new ItemStack(Items.IRON_INGOT)));
            helper.assertTrue(JournalProbeAccessor.journalCount(rig.player().server) > 0,
                    "PREPARED journal must be durable before mutation");

            rig.storage().extract(diamond, 1, Actionable.MODULATE, source);
            rig.player().getInventory().getItem(0).shrink(2);
            rig.storage().insert(iron, 1, Actionable.MODULATE, source);
            ViScriptShopServerUtil.setMoney(rig.player(), 17);
            rig.player().giveExperiencePoints(9);
            ViScriptShopServerUtil.reduceMerchantStock(rig.player(), rig.shop(), "items", rig.merchant(), 1);

            helper.assertTrue(JournalProbeAccessor.replayFromDisk(rig.player().server, rig.player(), rig.shop()),
                    "reloaded PREPARED journal must replay");
            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) == 2 && count(rig.player(), Items.DIAMOND) == 0,
                    "PREPARED replay restores full player snapshot");
            helper.assertTrue(rig.storage().extract(diamond, 1, Actionable.SIMULATE, source) == 1
                            && rig.storage().extract(iron, 2, Actionable.SIMULATE, source) == 0,
                    "PREPARED replay restores exact ME pre-state");
            helper.assertTrue(ViScriptShopServerUtil.getMoney(rig.player()) == 0 && rig.player().totalExperience == 0,
                    "PREPARED replay restores economy pre-state");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 1, "PREPARED replay restores stock pre-state");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void committedJournalReloadCompletesForward(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "reload-committed", 1);
        helper.runAfterDelay(30, () -> {
            IActionSource source = IActionSource.ofPlayer(rig.player());
            AEItemKey diamond = AEItemKey.of(Items.DIAMOND);
            AEItemKey iron = AEItemKey.of(Items.IRON_INGOT);
            rig.storage().insert(diamond, 1, Actionable.MODULATE, source);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            Counters committed = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(committed.success == 1, "fixture transaction must commit before replay simulation");
            helper.assertTrue(JournalProbeAccessor.hasJournal(rig.player().server, rig.shop()),
                    "COMMITTED journal remains until normal checkpoint");

            rig.player().getInventory().clearContent();
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            rig.storage().extract(iron, 2, Actionable.MODULATE, source);
            rig.storage().insert(diamond, 1, Actionable.MODULATE, source);
            rig.player().totalExperience = 0;
            rig.player().experienceLevel = 0;
            rig.player().experienceProgress = 0;
            ViScriptShopServerUtil.setMerchantStock(rig.shop(), "items", "diamond", 1);

            JournalProbeAccessor.simulateNewProcess(rig.player().server, rig.shop());
            helper.assertTrue(JournalProbeAccessor.replayFromDisk(rig.player().server, rig.player(), rig.shop()),
                    "reloaded COMMITTED journal must replay forward");
            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) == 0 && count(rig.player(), Items.DIAMOND) == 1,
                    "COMMITTED replay restores post inventory");
            helper.assertTrue(rig.storage().extract(iron, 2, Actionable.SIMULATE, source) == 2
                            && rig.storage().extract(diamond, 1, Actionable.SIMULATE, source) == 0,
                    "COMMITTED replay restores exact ME post-state");
            helper.assertTrue(rig.player().totalExperience == 3, "COMMITTED replay restores post XP");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 0, "COMMITTED replay restores post stock");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 240)
    public static void walLatencyAndFiftyTradeConservation(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "wal-benchmark", 50);
        helper.runAfterDelay(30, () -> {
            IActionSource source = IActionSource.ofPlayer(rig.player());
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 50, Actionable.MODULATE, source);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 64));
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 36));
            List<Long> micros = new ArrayList<>();
            Counters counters = new Counters();
            NeoForge.EVENT_BUS.register(counters);
            try {
                for (int index = 0; index < 50; index++) {
                    long started = System.nanoTime();
                    invokeAuthoritativeBuy(rig.player(), rig.shop());
                    micros.add((System.nanoTime() - started) / 1_000);
                }
            } finally {
                NeoForge.EVENT_BUS.unregister(counters);
            }
            Collections.sort(micros);
            long p50 = micros.get(24);
            long p95 = micros.get(47);
            cn.scex.viscriptshopae2.ScexViScriptShopAe2.LOGGER.info(
                    "WAL_BENCHMARK transactions=50 p50_us={} p95_us={} max_us={}", p50, p95, micros.getLast());
            helper.assertTrue(counters.success == 50 && counters.fail == 0, "all benchmark trades must commit");
            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) == 0 && count(rig.player(), Items.DIAMOND) == 50,
                    "50 trades conserve player inventory");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(Items.IRON_INGOT), 100, Actionable.SIMULATE, source) == 100
                            && rig.storage().extract(AEItemKey.of(Items.DIAMOND), 50, Actionable.SIMULATE, source) == 0,
                    "50 trades conserve ME inventory");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 0, "50 trades decrement stock exactly");
            helper.succeed();
        });
    }

    private static void runInjectedRollback(GameTestHelper helper, String name, FaultPoint point) {
        TestRig rig = setupRig(helper, name, 1);
        helper.runAfterDelay(30, () -> {
            IActionSource source = IActionSource.ofPlayer(rig.player());
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1, Actionable.MODULATE, source);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            int money = ViScriptShopServerUtil.getMoney(rig.player());
            int xp = rig.player().totalExperience;
            Counters counters = new Counters();
            NeoForge.EVENT_BUS.register(counters);
            try {
                invokeCore(rig, new FaultStorage(rig.storage(), point));
            } finally {
                NeoForge.EVENT_BUS.unregister(counters);
            }
            helper.assertTrue(count(rig.player(), Items.IRON_INGOT) == 2, "rollback restores player payment");
            helper.assertTrue(count(rig.player(), Items.DIAMOND) == 0, "rollback removes granted goods");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(Items.DIAMOND), 1, Actionable.SIMULATE, source) == 1,
                    "rollback restores extracted ME goods");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(Items.IRON_INGOT), 2, Actionable.SIMULATE, source) == 0,
                    "rollback removes inserted ME payment, including partial insert");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 1, "rollback restores shop stock");
            helper.assertTrue(ViScriptShopServerUtil.getMoney(rig.player()) == money && rig.player().totalExperience == xp,
                    "rollback restores money and XP");
            helper.assertTrue(counters.success == 0 && counters.fail == 1, "rollback emits BuyFail only");
            helper.succeed();
        });
    }

    private static TestRig setupRig(GameTestHelper helper, String shop, int stock) {
        shop = shop + "-" + UUID.randomUUID();
        ServerPlayer player = makePlayer(helper, shop);
        BlockPos playerPos = helper.absolutePos(TERMINAL).relative(Direction.NORTH);
        player.setPos(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5);
        player.setGameMode(GameType.SURVIVAL);
        helper.setBlock(POWER, AEBlocks.CREATIVE_ENERGY_CELL.block());
        helper.setBlock(DRIVE, AEBlocks.DRIVE.block());
        DriveBlockEntity drive = (DriveBlockEntity) helper.getBlockEntity(DRIVE);
        drive.getInternalInventory().setItemDirect(0, AEItems.ITEM_CELL_64K.stack());
        var host = PartHelper.getOrPlacePartHost(helper.getLevel(), helper.absolutePos(TERMINAL), true, player);
        helper.assertTrue(host != null, "AE2 multipart host must be created");
        host.addPart((appeng.api.parts.IPartItem<?>) AEParts.GLASS_CABLE.item(AEColor.TRANSPARENT), null, player);
        ItemTerminalPart terminal = PartHelper.setPart(helper.getLevel(), helper.absolutePos(TERMINAL), Direction.NORTH,
                player, AEParts.TERMINAL.get());
        helper.assertTrue(terminal != null, "AE2 multipart terminal must be created");
        BlockPos absolute = helper.absolutePos(TERMINAL);
        BridgeConfigAccessor.setBinding(shop, absolute);

        MerchantInfo merchant = new MerchantInfo();
        merchant.setId("diamond");
        merchant.setItemA(new ItemStack(Items.IRON_INGOT, 2));
        merchant.setItemResult(new ItemStack(Items.DIAMOND));
        merchant.setStock(stock);
        merchant.setXp(3);
        CategoryInfo category = new CategoryInfo();
        category.setId("items");
        category.setShopType(CategoryInfo.ShopType.ITEM_FOR_ITEM);
        category.getMerchants().add(merchant);
        ShopInfo info = new ShopInfo(); info.getCategoryInfos().add(category);
        if (ViscriptShop.getShopSavedData() == null) ViscriptShop.setShopSavedData(new ShopSavedData());
        ViScriptShopServerUtil.setShopInfo(shop, info);
        return new TestRig(player, shop, merchant, info, terminal, terminal.getInventory());
    }

    private static ServerPlayer makePlayer(GameTestHelper helper, String name) {
        UUID playerId = UUID.nameUUIDFromBytes(("scex-gametest-" + name).getBytes(StandardCharsets.UTF_8));
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(playerId, "scex-" + name));
        BlockPos playerPos = helper.absolutePos(TERMINAL).relative(Direction.NORTH);
        player.setPos(playerPos.getX() + 0.5, playerPos.getY(), playerPos.getZ() + 0.5);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static Counters invokeCounted(ServerPlayer player, String shop) {
        Counters counters = new Counters();
        NeoForge.EVENT_BUS.register(counters);
        try {
            invokeAuthoritativeBuy(player, shop);
        } finally {
            NeoForge.EVENT_BUS.unregister(counters);
        }
        cn.scex.viscriptshopae2.ScexViScriptShopAe2.LOGGER.info(
                "GameTest purchase shop={} player={} success={} fail={} iron={} diamond={}", shop,
                player.getGameProfile().getName(), counters.success, counters.fail,
                count(player, Items.IRON_INGOT), count(player, Items.DIAMOND));
        return counters;
    }

    private static void invokeAuthoritativeBuy(ServerPlayer player, String shop) {
        AggregatedResources request = new AggregatedResources();
        request.getPurchaseEntries().add(new AggregatedResources.PurchaseEntry("items", "diamond", 1));
        try {
            BuyMerchantPayload.buyMerchant(RPCSender.ofClient(player), shop, new AggregatedResources(), request);
        } catch (Throwable failure) {
            cn.scex.viscriptshopae2.ScexViScriptShopAe2.LOGGER.error(
                    "Authoritative GameTest purchase failed for shop {}", shop, failure);
            throw failure;
        }
    }

    private static void invokeCore(TestRig rig, appeng.api.storage.MEStorage storage) {
        ShopServerEvent.BuyPre event = makeEvent(rig);
        try {
            Method execute = cn.scex.viscriptshopae2.AtomicTradeHandler.class.getDeclaredMethod("execute",
                    ServerPlayer.class, String.class, cn.scex.viscriptshopae2.TerminalBinding.class,
                    ShopServerEvent.BuyPre.class, appeng.api.storage.MEStorage.class);
            execute.setAccessible(true);
            var binding = cn.scex.viscriptshopae2.TerminalBinding.find(rig.shop()).orElseThrow();
            execute.invoke(null, rig.player(), rig.shop(), binding, event, storage);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new AssertionError("Core invocation escaped transaction boundary", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot invoke transaction core", failure);
        }
    }

    private static ShopServerEvent.BuyPre makeEvent(TestRig rig) {
        AggregatedResources cost = new AggregatedResources();
        cost.addItemEntry(rig.merchant().getItemA(), rig.merchant().getItemA().getCount(),
                rig.merchant().getItemAMatchRule());
        AggregatedResources gain = new AggregatedResources();
        gain.addItem(rig.merchant().getItemResult(), rig.merchant().getItemResult().getCount());
        gain.setTotalXp(rig.merchant().getXp());
        gain.getPurchaseEntries().add(new AggregatedResources.PurchaseEntry("items", "diamond", 1));
        return new ShopServerEvent.BuyPre(rig.player(), rig.info(), cost, gain);
    }

    private static int count(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) count += stack.getCount();
        return count;
    }

    private record TestRig(ServerPlayer player, String shop, MerchantInfo merchant, ShopInfo info,
                           ItemTerminalPart terminal,
                           appeng.api.storage.MEStorage storage) {}

    private enum FaultPoint { AFTER_GOODS_EXTRACT, BEFORE_PAYMENT_INSERT, AFTER_PARTIAL_PAYMENT_INSERT }

    private static final class FaultStorage implements appeng.api.storage.MEStorage {
        private final appeng.api.storage.MEStorage delegate;
        private final FaultPoint point;

        private FaultStorage(appeng.api.storage.MEStorage delegate, FaultPoint point) {
            this.delegate = delegate;
            this.point = point;
        }

        @Override
        public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.MODULATE && key instanceof AEItemKey item && item.getReadOnlyStack().is(Items.IRON_INGOT)) {
                if (point == FaultPoint.BEFORE_PAYMENT_INSERT) throw new IllegalStateException("injected before insert");
                if (point == FaultPoint.AFTER_PARTIAL_PAYMENT_INSERT) {
                    delegate.insert(key, Math.max(1, amount / 2), mode, source);
                    throw new IllegalStateException("injected after partial insert");
                }
            }
            return delegate.insert(key, amount, mode, source);
        }

        @Override
        public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            long done = delegate.extract(key, amount, mode, source);
            if (mode == Actionable.MODULATE && point == FaultPoint.AFTER_GOODS_EXTRACT
                    && key instanceof AEItemKey item && item.getReadOnlyStack().is(Items.DIAMOND)) {
                throw new IllegalStateException("injected after goods extract");
            }
            return done;
        }

        @Override public void getAvailableStacks(KeyCounter out) { delegate.getAvailableStacks(out); }
        @Override public Component getDescription() { return delegate.getDescription(); }
    }

    public static final class Counters {
        int success; int fail;
        @SubscribeEvent public void success(ShopServerEvent.BuySuccess event) { success++; }
        @SubscribeEvent public void fail(ShopServerEvent.BuyFail event) { fail++; }
    }
}
