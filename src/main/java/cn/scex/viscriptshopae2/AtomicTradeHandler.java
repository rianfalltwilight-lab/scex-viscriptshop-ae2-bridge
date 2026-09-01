package cn.scex.viscriptshopae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.lowdragmc.lowdraglib2.networking.rpc.RPCPacketDistributor;
import com.viscriptshop.Config;
import com.viscriptshop.event.neoforge.ShopServerEvent;
import com.viscriptshop.gui.components.Message;
import com.viscriptshop.gui.data.AggregatedResources;
import com.viscriptshop.gui.data.CategoryInfo;
import com.viscriptshop.gui.data.MerchantFlagGroup;
import com.viscriptshop.gui.data.MerchantInfo;
import com.viscriptshop.network.c2s.BuyMerchantPayload;
import com.viscriptshop.network.s2c.S2CPayload;
import com.viscriptshop.util.ViScriptShopServerUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;

@EventBusSubscriber(modid = ScexViScriptShopAe2.MOD_ID)
public final class AtomicTradeHandler {
    private AtomicTradeHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBuy(ShopServerEvent.BuyPre event) {
        String shop = BridgeContext.shop();
        if (shop == null) return;
        ServerPlayer player = event.getPlayer();
        var binding = ConnectorBinding.find(player);
        if (binding.isEmpty()) return;
        var resolved = binding.get().resolve(player.server);
        if (resolved.isEmpty()) return;
        event.setCanceled(true);
        if (!TradeJournal.ensureReady(player.server)) { fail(player, event, "error.rollback"); return; }
        var connector = resolved.get();
        if (!connector.level().mayInteract(player, binding.get().pos())) {
            fail(player, event, "error.permission"); return;
        }
        synchronized (connector.grid()) {
            execute(player, shop, binding.get(), event, connector.storage());
        }
    }

    private static void execute(ServerPlayer player, String shop, ConnectorBinding binding,
                                ShopServerEvent.BuyPre event, MEStorage storage) {
        AggregatedResources cost = event.getCostSummary();
        AggregatedResources gain = event.getGainSummary();
        if (!validateShopRules(player, shop, event)) return;
        if (cost.getTotalMoney() < 0 || gain.getTotalMoney() < 0
                || cost.getTotalXp() < 0 || gain.getTotalXp() < 0) {
            fail(player, event, "error.rollback");
            return;
        }
        int maxGive = Config.maxShopUiGiveItemsPerPurchase.get();
        if (maxGive >= 0 && gain.getTotalItemCount() > maxGive) {
            fail(player, event, Component.translatable("viscript_shop.message.buy.too_many_items", maxGive)); return;
        }
        int moneyBefore = ViScriptShopServerUtil.getMoney(player);
        if (cost.getTotalMoney() > moneyBefore) {
            fail(player, event, Component.translatable("viscript_shop.message.noEnoughMoney",
                    cost.getTotalMoney() - moneyBefore)); return;
        }
        if (cost.getTotalXp() > player.totalExperience) {
            fail(player, event, Component.literal("Not enough experience"));
            return;
        }

        IActionSource source = IActionSource.ofPlayer(player);
        List<Need> paymentNeeds = cost.getItemEntries().stream()
                .map(e -> new Need(e.getItemStack(), e.getCount(), e)).toList();
        PaymentPlan paymentPlan = planPayments(storage, player.getInventory(), paymentNeeds, source);
        if (paymentPlan == null) {
            ItemStack missing = paymentNeeds.isEmpty() ? ItemStack.EMPTY : paymentNeeds.getFirst().stack();
            fail(player, event, Component.translatable("viscript_shop.message.notEnoughItem",
                    missing.isEmpty() ? "?" : missing.getItem().getDescription().getString())); return;
        }
        List<Move> goods = gain.getItems().entrySet().stream()
                .map(entry -> new Move(AEItemKey.of(entry.getKey()), entry.getValue())).toList();
        if (goods.stream().anyMatch(move -> move.key() == null)
                || !canInsert(storage, goods, source)) { fail(player, event, "error.capacity"); return; }
        InventoryPlan inventoryPlan = planInventoryCommit(player.getInventory(), paymentPlan.inventoryDebits());
        if (!canExtract(storage, paymentPlan.networkDebits(), source) || !canInsert(storage, goods, source)) {
            fail(player, event, "error.offline");
            return;
        }

        List<ItemStack> affectedKeys = new ArrayList<>();
        goods.forEach(move -> affectedKeys.add(move.key().getReadOnlyStack()));
        paymentPlan.networkDebits().forEach(move -> affectedKeys.add(move.key().getReadOnlyStack()));
        Map<AEItemKey, Long> networkDeltas = new LinkedHashMap<>();
        try {
            paymentPlan.networkDebits().forEach(move ->
                    networkDeltas.merge(move.key(), -move.amount(), Math::addExact));
            goods.forEach(move -> networkDeltas.merge(move.key(), move.amount(), Math::addExact));
        } catch (ArithmeticException overflow) {
            fail(player, event, "error.rollback");
            return;
        }
        TradeJournal journal;
        try {
            journal = TradeJournal.prepare(player, shop, binding, event, storage, affectedKeys,
                    inventoryPlan.before(), inventoryPlan.after(), networkDeltas);
        } catch (RuntimeException failure) {
            ScexViScriptShopAe2.LOGGER.error("Cannot prepare durable ME transaction player={} shop={}",
                    player.getGameProfile().getName(), shop, failure);
            fail(player, event, "error.rollback");
            return;
        }

        try {
            modulateExtract(storage, paymentPlan.networkDebits(), new ArrayList<>(), source);
            applyInventoryPlan(player.getInventory(), inventoryPlan);
            modulateInsert(storage, goods, new ArrayList<>(), source);
            if (cost.getTotalMoney() > 0) ViScriptShopServerUtil.removeMoney(player, cost.getTotalMoney());
            if (cost.getTotalXp() > 0) player.giveExperiencePoints(-cost.getTotalXp());
            reduceStock(player, shop, event);
            if (gain.getTotalMoney() > 0) ViScriptShopServerUtil.addMoney(player, gain.getTotalMoney());
            if (gain.getTotalXp() > 0) player.giveExperiencePoints(gain.getTotalXp());
            journal.commit(player, storage);
        } catch (RuntimeException failure) {
            boolean recovered = journal.rollbackInProcess(player);
            if (!recovered) TradeJournal.markRecoveryPending();
            ScexViScriptShopAe2.LOGGER.error(
                    "ME transaction aborted player={} shop={} recovered={} journal={}",
                    player.getGameProfile().getName(), shop, recovered, recovered ? "removed" : "pending", failure);
            fail(player, event, "error.rollback"); return;
        }

        for (String command : gain.getCommands()) {
            try {
                BuyMerchantPayload.executeCommands(player, command);
            } catch (RuntimeException commandFailure) {
                ScexViScriptShopAe2.LOGGER.error(
                        "Command reward failed after durable transaction player={} shop={} command={}",
                        player.getGameProfile().getName(), shop, command, commandFailure);
            }
        }

        try {
            NeoForge.EVENT_BUS.post(new ShopServerEvent.BuySuccess(player, event.getShopInfo(), cost, gain));
        } catch (RuntimeException listenerFailure) {
            ScexViScriptShopAe2.LOGGER.error("BuySuccess listener failed after durable transaction player={} shop={}",
                    player.getGameProfile().getName(), shop, listenerFailure);
        }
        safeRpc(player, S2CPayload.SEND_MESSAGE, Message.Type.SUCCESS,
                Component.translatable("viscript_shop.message.buySuccess"));
        safeRpc(player, S2CPayload.RELOAD_SHOP_UI,
                ViScriptShopServerUtil.getPlayerVisibleShopInfo(player, shop, event.getShopInfo()), cost);
    }

    private static void modulateExtract(MEStorage storage, List<Move> planned, List<Move> completed,
                                        IActionSource source) {
        for (Move move : planned) {
            long done = storage.extract(move.key(), move.amount(), Actionable.MODULATE, source);
            if (done > 0) completed.add(new Move(move.key(), done));
            if (done != move.amount()) throw new TransactionFailure("goods changed during commit");
        }
    }

    private static void modulateInsert(MEStorage storage, List<Move> planned, List<Move> completed,
                                       IActionSource source) {
        for (Move move : planned) {
            long done = storage.insert(move.key(), move.amount(), Actionable.MODULATE, source);
            if (done > 0) completed.add(new Move(move.key(), done));
            if (done != move.amount()) throw new TransactionFailure("capacity changed during commit");
        }
    }

    private static PaymentPlan planPayments(MEStorage storage, Inventory inventory, List<Need> needs,
                                            IActionSource source) {
        Map<AEItemKey, Long> network = new LinkedHashMap<>();
        Map<Integer, SlotDebit> planned = new LinkedHashMap<>();
        var available = storage.getAvailableStacks();
        for (Need need : needs) {
            long remaining = need.amount();
            for (var entry : available) {
                if (!(entry.getKey() instanceof AEItemKey key) || !matches(need, key.getReadOnlyStack())) continue;
                long unused = entry.getLongValue() - network.getOrDefault(key, 0L);
                long take = Math.min(remaining, Math.max(0, unused));
                if (take > 0) {
                    network.merge(key, take, Math::addExact);
                    remaining -= take;
                }
                if (remaining == 0) break;
            }
            for (int slot = 0; slot < inventory.getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = inventory.getItem(slot);
                if (stack.isEmpty() || !matches(need, stack)) continue;
                int reserved = planned.containsKey(slot) ? planned.get(slot).amount() : 0;
                int take = (int) Math.min(remaining, Math.max(0, stack.getCount() - reserved));
                if (take > 0) {
                    final int slotIndex = slot;
                    planned.compute(slot, (ignored, old) -> new SlotDebit(slotIndex, stack.copy(),
                            take + (old == null ? 0 : old.amount())));
                    remaining -= take;
                }
            }
            if (remaining != 0) return null;
        }
        List<Move> networkDebits = network.entrySet().stream()
                .map(entry -> new Move(entry.getKey(), entry.getValue())).toList();
        if (!canExtract(storage, networkDebits, source)) return null;
        return new PaymentPlan(networkDebits, List.copyOf(planned.values()));
    }

    private static InventoryPlan planInventoryCommit(Inventory inventory, List<SlotDebit> debits) {
        List<ItemStack> before = new ArrayList<>(inventory.getContainerSize());
        List<ItemStack> after = new ArrayList<>(inventory.getContainerSize());
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            before.add(inventory.getItem(slot).copy());
            after.add(inventory.getItem(slot).copy());
        }
        for (SlotDebit debit : debits) after.get(debit.slot()).shrink(debit.amount());
        return new InventoryPlan(List.copyOf(before), List.copyOf(after));
    }

    private static void applyInventoryPlan(Inventory inventory, InventoryPlan plan) {
        for (int slot = 0; slot < plan.before().size(); slot++) {
            if (!ItemStack.matches(inventory.getItem(slot), plan.before().get(slot))) {
                throw new TransactionFailure("player inventory changed during commit");
            }
        }
        for (int slot = 0; slot < plan.after().size(); slot++) inventory.setItem(slot, plan.after().get(slot).copy());
        inventory.setChanged();
    }

    private static boolean canInsert(MEStorage storage, List<Move> moves, IActionSource source) {
        for (Move move : moves) {
            if (storage.insert(move.key(), move.amount(), Actionable.SIMULATE, source) != move.amount()) return false;
        }
        return true;
    }

    private static boolean canExtract(MEStorage storage, List<Move> moves, IActionSource source) {
        for (Move move : moves) {
            if (storage.extract(move.key(), move.amount(), Actionable.SIMULATE, source) != move.amount()) return false;
        }
        return true;
    }

    private static boolean matches(Need need, ItemStack candidate) {
        return need.entry() == null ? ItemStack.isSameItemSameComponents(need.stack(), candidate)
                : need.entry().getMatchRule().matches(candidate, need.stack());
    }

    private static boolean validateShopRules(ServerPlayer player, String shop, ShopServerEvent.BuyPre event) {
        Map<PurchaseKey, Long> requested = new LinkedHashMap<>();
        for (var entry : event.getGainSummary().getPurchaseEntries()) {
            CategoryInfo category = event.getShopInfo().getCategoryInfos().stream()
                    .filter(c -> c.getId().equals(entry.getCategoryId())).findFirst().orElse(null);
            if (category == null) { fail(player, event, "error.rollback"); return false; }
            MerchantInfo merchant = category.getMerchants().stream()
                    .filter(m -> m.getId().equals(entry.getMerchantId())).findFirst().orElse(null);
            if (merchant == null || entry.getBuyCount() <= 0) { fail(player, event, "error.rollback"); return false; }
            if (!MerchantFlagGroup.canAccess(merchant.getFlagGroupMode(), merchant.getFlagGroups(),
                    ViScriptShopServerUtil.getStageFlags(player))) {
                fail(player, event, Component.translatable("viscript_shop.message.stage_flags.missing")); return false;
            }
            try {
                requested.merge(new PurchaseKey(category.getId(), merchant.getId()), (long) entry.getBuyCount(),
                        Math::addExact);
            } catch (ArithmeticException overflow) {
                fail(player, event, "error.rollback");
                return false;
            }
        }
        for (var request : requested.entrySet()) {
            CategoryInfo category = event.getShopInfo().getCategoryInfos().stream()
                    .filter(c -> c.getId().equals(request.getKey().category())).findFirst().orElseThrow();
            MerchantInfo merchant = category.getMerchants().stream()
                    .filter(m -> m.getId().equals(request.getKey().merchant())).findFirst().orElseThrow();
            int stock = ViScriptShopServerUtil.getEffectiveMerchantStock(player, shop, category.getId(), merchant);
            if (stock >= 0 && request.getValue() > stock) {
                fail(player, event, Component.translatable("viscript_shop.message.shoppingCart.out_of_stock"));
                return false;
            }
        }
        return true;
    }

    private static void reduceStock(ServerPlayer player, String shop, ShopServerEvent.BuyPre event) {
        for (var entry : event.getGainSummary().getPurchaseEntries()) {
            var category = event.getShopInfo().getCategoryInfos().stream()
                    .filter(c -> c.getId().equals(entry.getCategoryId())).findFirst().orElse(null);
            if (category == null) continue;
            var merchant = category.getMerchants().stream()
                    .filter(m -> m.getId().equals(entry.getMerchantId())).findFirst().orElse(null);
            if (merchant != null) ViScriptShopServerUtil.reduceMerchantStock(player, shop, category.getId(), merchant,
                    entry.getBuyCount());
        }
    }

    private static void fail(ServerPlayer player, ShopServerEvent.BuyPre event, String suffix) {
        fail(player, event, Component.translatable("scex_viscriptshop_ae2." + suffix));
    }

    private static void fail(ServerPlayer player, ShopServerEvent.BuyPre event, Component component) {
        ScexViScriptShopAe2.LOGGER.debug("Rejected bridged trade player={} shop={} reason={}",
                player.getGameProfile().getName(), BridgeContext.shop(), component.getString());
        safeRpc(player, S2CPayload.SEND_MESSAGE, Message.Type.ERROR, component);
        NeoForge.EVENT_BUS.post(new ShopServerEvent.BuyFail(player, event.getShopInfo(),
                event.getCostSummary(), event.getGainSummary()));
    }

    private static void safeRpc(ServerPlayer player, String method, Object... arguments) {
        try {
            RPCPacketDistributor.rpcToPlayer(player, method, arguments);
        } catch (RuntimeException disconnected) {
            ScexViScriptShopAe2.LOGGER.warn("Skipping shop RPC {} for unavailable player connection {}: {}",
                    method, player.getGameProfile().getName(), disconnected.toString());
        }
    }

    private record Need(ItemStack stack, long amount, AggregatedResources.ItemEntry entry) {}
    private record Move(AEItemKey key, long amount) {}
    private record SlotDebit(int slot, ItemStack original, int amount) {}
    private record PaymentPlan(List<Move> networkDebits, List<SlotDebit> inventoryDebits) {}
    private record PurchaseKey(String category, String merchant) {}
    private record InventoryPlan(List<ItemStack> before, List<ItemStack> after) {}
    private static final class TransactionFailure extends RuntimeException { TransactionFailure(String m) { super(m); } }
}
