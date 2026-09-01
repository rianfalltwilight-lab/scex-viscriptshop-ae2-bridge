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
import cn.scex.viscriptshopae2.ConnectorBinding;
import cn.scex.viscriptshopae2.ConnectorInventory;
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
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.common.util.FakePlayerFactory;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder("scex_viscriptshop_ae2")
@PrefixGameTestTemplate(false)
public final class BridgeGameTests {
    private static final BlockPos CONNECTOR = new BlockPos(2, 2, 2);
    private static final BlockPos DRIVE = new BlockPos(3, 2, 2);
    private static final BlockPos POWER = new BlockPos(4, 2, 2);

    private BridgeGameTests() {}

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void connectorJoinsOwnersMeNetwork(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "online", 1);
        helper.runAfterDelay(30, () -> {
            helper.assertTrue(rig.connector().getMainNode().isOnline(), "connector must join the adjacent ME network");
            helper.assertTrue(rig.connector().getMainNode().getNode().getOwningPlayerProfileId()
                    .equals(rig.player().getUUID()), "placed connector must retain player ownership");
            helper.assertTrue(ConnectorBinding.find(rig.player()).flatMap(value -> value.resolve(rig.player().server))
                    .isPresent(), "owner link must resolve without an operator configured coordinate");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void nativeShopSellsFromMeAndBuysIntoMe(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "two-way", 1);
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 2);
            Counters counters = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(meCount(rig, Items.IRON_INGOT) == 0, "item payment must be extracted from ME");
            helper.assertTrue(meCount(rig, Items.DIAMOND) == 1, "purchased item must be inserted into ME");
            helper.assertTrue(inventoryCount(rig.player(), Items.IRON_INGOT) == 0
                    && inventoryCount(rig.player(), Items.DIAMOND) == 0, "pure ME trade must not touch inventory");
            helper.assertTrue(counters.success == 1 && counters.fail == 0, "one BuySuccess expected");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 0, "native stock must decrement once");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void meAndBackpackCanJointlyPay(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "combined", 1);
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 1);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT));
            Counters counters = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(counters.success == 1, "combined ME and inventory payment must succeed");
            helper.assertTrue(meCount(rig, Items.IRON_INGOT) == 0
                    && inventoryCount(rig.player(), Items.IRON_INGOT) == 0, "combined payment must debit exactly two");
            helper.assertTrue(meCount(rig, Items.DIAMOND) == 1, "combined payment reward must enter ME");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void insufficientCombinedPaymentDoesNotMutate(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "insufficient", 1);
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 1);
            Counters counters = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(counters.success == 0 && counters.fail == 1, "insufficient trade must emit BuyFail");
            helper.assertTrue(meCount(rig, Items.IRON_INGOT) == 1 && meCount(rig, Items.DIAMOND) == 0,
                    "failed trade must preserve the ME network");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 1, "failed trade must preserve stock");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void fullMeRejectsPurchaseBeforePayment(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "capacity", 1);
        helper.runAfterDelay(30, () -> {
            IActionSource source = source(rig);
            insert(rig, Items.IRON_INGOT, 2);
            rig.storage().insert(AEItemKey.of(Items.COBBLESTONE), Long.MAX_VALUE, Actionable.MODULATE, source);
            helper.assertTrue(rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1,
                    Actionable.SIMULATE, source) == 0, "fixture must not accept a new purchased item");
            Counters counters = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(counters.success == 0 && counters.fail == 1, "capacity failure must emit BuyFail");
            helper.assertTrue(meCount(rig, Items.IRON_INGOT) == 2 && meCount(rig, Items.DIAMOND) == 0,
                    "capacity failure must not debit payment");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void componentRulesApplyInsideMe(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "components", 1);
        ItemStack named = new ItemStack(Items.IRON_INGOT, 2);
        named.set(DataComponents.CUSTOM_NAME, Component.literal("SCEX token"));
        rig.merchant().setItemA(named.copy());
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 2);
            helper.assertTrue(invokeCounted(rig.player(), rig.shop()).fail == 1,
                    "plain ME iron must not match component-bearing price");
            helper.assertTrue(meCount(rig, Items.DIAMOND) == 0, "mismatched trade must not grant goods");
            rig.storage().insert(AEItemKey.of(named), 2, Actionable.MODULATE, source(rig));
            helper.assertTrue(invokeCounted(rig.player(), rig.shop()).success == 1,
                    "matching component-bearing ME item must pay");
            helper.assertTrue(rig.storage().extract(AEItemKey.of(named), Long.MAX_VALUE,
                    Actionable.SIMULATE, source(rig)) == 0, "exact component variant must be removed");
            helper.assertTrue(meCount(rig, Items.DIAMOND) == 1, "purchased item must enter ME");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void itemCountCombinesBackpackAndMe(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "count", 1);
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 5);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 3));
            AggregatedResources.ItemEntry entry = new AggregatedResources.ItemEntry(
                    new ItemStack(Items.IRON_INGOT), 1, rig.merchant().getItemAMatchRule());
            int nativeCount = entry.getItemForPlayerCount(rig.player());
            int networkCount = ConnectorInventory.countVisibleItems(rig.player(), entry);
            helper.assertTrue(nativeCount == 3 && networkCount == 5
                    && ConnectorInventory.saturatingAdd(nativeCount, networkCount) == 8,
                    "shop availability must combine backpack and connected ME counts");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 120)
    public static void missingConnectorPreservesNativeShopBehavior(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "native-fallback", 1);
        helper.runAfterDelay(30, () -> {
            helper.setBlock(CONNECTOR, Blocks.AIR);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT, 2));
            Counters counters = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(counters.success == 1 && counters.fail == 0, "offline connector must delegate to native shop");
            helper.assertTrue(inventoryCount(rig.player(), Items.IRON_INGOT) == 0
                    && inventoryCount(rig.player(), Items.DIAMOND) == 1,
                    "native fallback must debit and grant through player inventory");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 140)
    public static void xpAndStockCommitWithMeItems(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "xp-stock", 1);
        rig.merchant().setXp(4);
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 2);
            int before = rig.player().totalExperience;
            Counters counters = invokeCounted(rig.player(), rig.shop());
            helper.assertTrue(counters.success == 1 && rig.player().totalExperience == before + 4,
                    "native XP gain must commit once beside ME item movement");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 0, "native stock must commit beside ME item movement");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void failureAfterMeRewardInsertionRollsBackEverything(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "rollback", 1);
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 1);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT));
            Counters counters = new Counters();
            NeoForge.EVENT_BUS.register(counters);
            try {
                invokeCore(rig, new FaultStorage(rig.storage()));
            } finally {
                NeoForge.EVENT_BUS.unregister(counters);
            }
            helper.assertTrue(counters.success == 0 && counters.fail == 1, "rolled-back trade must emit BuyFail only");
            helper.assertTrue(meCount(rig, Items.IRON_INGOT) == 1 && meCount(rig, Items.DIAMOND) == 0,
                    "rollback must restore ME payment and remove inserted reward");
            helper.assertTrue(inventoryCount(rig.player(), Items.IRON_INGOT) == 1,
                    "rollback must restore inventory payment remainder");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 1, "rollback must restore native stock");
            helper.assertTrue(noDroppedItems(helper), "rollback must never create item entities");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void preparedWalRestoresMeAndInventory(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "prepared", 1);
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 1);
            rig.player().getInventory().add(new ItemStack(Items.IRON_INGOT));
            ConnectorBinding binding = ConnectorBinding.find(rig.player()).orElseThrow();
            JournalProbeAccessor.prepare(rig.player(), rig.shop(), binding, makeEvent(rig), rig.storage(),
                    List.of(new ItemStack(Items.IRON_INGOT), new ItemStack(Items.DIAMOND)));
            rig.storage().extract(AEItemKey.of(Items.IRON_INGOT), 1, Actionable.MODULATE, source(rig));
            JournalProbeAccessor.applyLatestPostInventory(rig.player().server, rig.player(), rig.shop());
            rig.storage().insert(AEItemKey.of(Items.DIAMOND), 1, Actionable.MODULATE, source(rig));
            helper.assertTrue(JournalProbeAccessor.replayFromDisk(rig.player().server, rig.player(), rig.shop()),
                    "PREPARED WAL must roll back");
            helper.assertTrue(meCount(rig, Items.IRON_INGOT) == 1 && meCount(rig, Items.DIAMOND) == 0
                    && inventoryCount(rig.player(), Items.IRON_INGOT) == 1,
                    "PREPARED rollback must restore both inventories");
            helper.succeed();
        });
    }

    @GameTest(template = "empty", timeoutTicks = 160)
    public static void committedWalConfirmsForwardState(GameTestHelper helper) {
        TestRig rig = setupRig(helper, "committed", 1);
        helper.runAfterDelay(30, () -> {
            insert(rig, Items.IRON_INGOT, 2);
            helper.assertTrue(invokeCounted(rig.player(), rig.shop()).success == 1, "fixture trade must commit");
            JournalProbeAccessor.simulateNewProcessAndReplayAll(rig.player().server);
            helper.assertTrue(meCount(rig, Items.IRON_INGOT) == 0 && meCount(rig, Items.DIAMOND) == 1,
                    "COMMITTED WAL confirmation must preserve forward ME state");
            helper.assertTrue(ViScriptShopServerUtil.getEffectiveMerchantStock(rig.player(), rig.shop(), "items",
                    rig.merchant()) == 0, "COMMITTED WAL confirmation must preserve stock");
            helper.succeed();
        });
    }

    private static TestRig setupRig(GameTestHelper helper, String name, int stock) {
        String shop = name + "-" + UUID.randomUUID();
        ServerPlayer player = makePlayer(helper, name);
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
        merchant.setStock(stock);
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
        return new TestRig(player, shop, merchant, info, connector, new LazyStorage(connector));
    }

    private static ServerPlayer makePlayer(GameTestHelper helper, String name) {
        UUID id = UUID.nameUUIDFromBytes(("scex-connector-test-" + name).getBytes(StandardCharsets.UTF_8));
        ServerPlayer player = FakePlayerFactory.get(helper.getLevel(), new GameProfile(id, "scex-" + name));
        BlockPos position = helper.absolutePos(CONNECTOR).relative(Direction.NORTH);
        player.setPos(position.getX() + 0.5, position.getY(), position.getZ() + 0.5);
        player.setGameMode(GameType.SURVIVAL);
        return player;
    }

    private static Counters invokeCounted(ServerPlayer player, String shop) {
        Counters counters = new Counters();
        NeoForge.EVENT_BUS.register(counters);
        try {
            AggregatedResources request = new AggregatedResources();
            request.getPurchaseEntries().add(new AggregatedResources.PurchaseEntry("items", "diamond", 1));
            BuyMerchantPayload.buyMerchant(RPCSender.ofClient(player), shop, new AggregatedResources(), request);
        } finally {
            NeoForge.EVENT_BUS.unregister(counters);
        }
        return counters;
    }

    private static void invokeCore(TestRig rig, MEStorage storage) {
        try {
            Method execute = cn.scex.viscriptshopae2.AtomicTradeHandler.class.getDeclaredMethod("execute",
                    ServerPlayer.class, String.class, ConnectorBinding.class,
                    ShopServerEvent.BuyPre.class, MEStorage.class);
            execute.setAccessible(true);
            execute.invoke(null, rig.player(), rig.shop(), ConnectorBinding.find(rig.player()).orElseThrow(),
                    makeEvent(rig), storage);
        } catch (java.lang.reflect.InvocationTargetException failure) {
            throw new AssertionError("Core invocation escaped transaction boundary", failure.getCause());
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot invoke transaction core", failure);
        }
    }

    private static ShopServerEvent.BuyPre makeEvent(TestRig rig) {
        AggregatedResources cost = new AggregatedResources();
        cost.addItemEntry(rig.merchant().getItemA().copyWithCount(1), rig.merchant().getItemA().getCount(),
                rig.merchant().getItemAMatchRule());
        AggregatedResources gain = new AggregatedResources();
        gain.addItem(rig.merchant().getItemResult().copyWithCount(1), rig.merchant().getItemResult().getCount());
        gain.setTotalXp(rig.merchant().getXp());
        gain.getPurchaseEntries().add(new AggregatedResources.PurchaseEntry("items", "diamond", 1));
        return new ShopServerEvent.BuyPre(rig.player(), rig.info(), cost, gain);
    }

    private static IActionSource source(TestRig rig) { return IActionSource.ofPlayer(rig.player()); }

    private static void insert(TestRig rig, net.minecraft.world.item.Item item, long amount) {
        long inserted = rig.storage().insert(AEItemKey.of(item), amount, Actionable.MODULATE, source(rig));
        if (inserted != amount) throw new AssertionError("Cannot seed ME fixture with " + item);
    }

    private static long meCount(TestRig rig, net.minecraft.world.item.Item item) {
        return rig.storage().extract(AEItemKey.of(item), Long.MAX_VALUE, Actionable.SIMULATE, source(rig));
    }

    private static int inventoryCount(ServerPlayer player, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : player.getInventory().items) if (stack.is(item)) count += stack.getCount();
        return count;
    }

    private static boolean noDroppedItems(GameTestHelper helper) {
        return helper.getLevel().getEntitiesOfClass(ItemEntity.class,
                new AABB(helper.absolutePos(CONNECTOR)).inflate(16)).isEmpty();
    }

    private record TestRig(ServerPlayer player, String shop, MerchantInfo merchant, ShopInfo info,
                           MeShopConnectorBlockEntity connector, MEStorage storage) {}

    private static final class LazyStorage implements MEStorage {
        private final MeShopConnectorBlockEntity connector;
        private LazyStorage(MeShopConnectorBlockEntity connector) { this.connector = connector; }
        private MEStorage delegate() {
            var grid = connector.getMainNode().getGrid();
            if (grid == null) throw new IllegalStateException("connector grid unavailable");
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

    private static final class FaultStorage implements MEStorage {
        private final MEStorage delegate;
        private FaultStorage(MEStorage delegate) { this.delegate = delegate; }
        @Override public long insert(AEKey key, long amount, Actionable mode, IActionSource source) {
            if (mode == Actionable.MODULATE && key instanceof AEItemKey item
                    && item.getReadOnlyStack().is(Items.DIAMOND)) {
                delegate.insert(key, amount, mode, source);
                throw new IllegalStateException("injected after ME reward insertion");
            }
            return delegate.insert(key, amount, mode, source);
        }
        @Override public long extract(AEKey key, long amount, Actionable mode, IActionSource source) {
            return delegate.extract(key, amount, mode, source);
        }
        @Override public void getAvailableStacks(KeyCounter out) { delegate.getAvailableStacks(out); }
        @Override public Component getDescription() { return delegate.getDescription(); }
    }

    public static final class Counters {
        int success;
        int fail;
        @SubscribeEvent public void success(ShopServerEvent.BuySuccess event) { success++; }
        @SubscribeEvent public void fail(ShopServerEvent.BuyFail event) { fail++; }
    }
}
