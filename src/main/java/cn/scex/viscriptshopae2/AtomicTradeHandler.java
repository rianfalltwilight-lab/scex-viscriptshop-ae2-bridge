package cn.scex.viscriptshopae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.viscriptshop.event.neoforge.ShopServerEvent;
import com.viscriptshop.gui.data.AggregatedResources;
import com.viscriptshop.gui.data.CategoryInfo;
import com.viscriptshop.gui.data.MerchantFlagGroup;
import com.viscriptshop.gui.data.MerchantInfo;
import com.viscriptshop.network.c2s.BuyMerchantPayload;
import com.viscriptshop.util.ViScriptShopServerUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.items.ItemHandlerHelper;

@EventBusSubscriber(modid = ScexViScriptShopAe2.MOD_ID)
public final class AtomicTradeHandler {
    private AtomicTradeHandler() {}

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public static void onBuy(ShopServerEvent.BuyPre event) {
        String shop = BridgeContext.shop();
        if (shop == null || TerminalBinding.find(shop).isEmpty()) return; // Unbound shops retain upstream behavior.
        event.setCanceled(true);
        ServerPlayer player = event.getPlayer();
        var binding = TerminalBinding.find(shop).orElseThrow();
        var resolved = binding.resolve(player.server);
        if (resolved.isEmpty()) { message(player, "error.offline"); return; }
        var terminal = resolved.get();
        if (!terminal.level().mayInteract(player, binding.pos())) { message(player, "error.permission"); return; }
        UUID owner = terminal.part().getGridNode().getOwningPlayerProfileId();
        if (BridgeConfig.REQUIRE_TERMINAL_OWNER.get() && (owner == null || !owner.equals(player.getUUID()))) {
            message(player, "error.permission"); return;
        }

        synchronized (terminal.part().getGridNode().getGrid()) {
            execute(player, shop, event, terminal.terminal().getInventory());
        }
    }

    private static void execute(ServerPlayer player, String shop, ShopServerEvent.BuyPre event, MEStorage storage) {
        if (!validateShopRules(player, shop, event)) return;
        AggregatedResources cost = event.getCostSummary();
        AggregatedResources gain = event.getGainSummary();
        if (cost.getTotalMoney() > ViScriptShopServerUtil.getMoney(player)) {
            player.sendSystemMessage(Component.translatable("viscript_shop.message.noEnoughMoney",
                    cost.getTotalMoney() - ViScriptShopServerUtil.getMoney(player)));
            return;
        }
        IActionSource source = IActionSource.ofPlayer(player);
        List<Move> goods = planExtract(storage, gain.getItems().entrySet().stream()
                .map(e -> new Need(e.getKey(), e.getValue(), null)).toList(), source);
        if (goods == null) { message(player, "error.stock"); return; }
        List<Need> payments = cost.getItemEntries().stream()
                .map(e -> new Need(e.getItemStack(), e.getCount(), e)).toList();
        List<Move> paymentMoves = planPaymentKeys(storage, payments, source);
        if (paymentMoves == null) { message(player, "error.capacity"); return; }

        List<Move> extracted = new ArrayList<>();
        List<Move> inserted = new ArrayList<>();
        try {
            for (Move m : goods) {
                long done = storage.extract(m.key(), m.amount(), Actionable.MODULATE, source);
                if (done != m.amount()) throw new TransactionFailure("goods changed during commit");
                extracted.add(m);
            }
            for (Move m : paymentMoves) {
                long done = storage.insert(m.key(), m.amount(), Actionable.MODULATE, source);
                if (done != m.amount()) throw new TransactionFailure("capacity changed during commit");
                inserted.add(m);
            }
        } catch (RuntimeException failure) {
            boolean restored = rollback(storage, source, extracted, inserted);
            ScexViScriptShopAe2.LOGGER.error("ME shop transaction aborted for player {} shop {}; rollback={}",
                    player.getGameProfile().getName(), shop, restored, failure);
            message(player, "error.rollback");
            return;
        }

        if (cost.getTotalMoney() > 0) ViScriptShopServerUtil.removeMoney(player, cost.getTotalMoney());
        reduceStock(player, shop, event);
        for (Move m : goods) {
            int left = Math.toIntExact(m.amount());
            while (left > 0) {
                int count = Math.min(left, m.key().getReadOnlyStack().getMaxStackSize());
                ItemHandlerHelper.giveItemToPlayer(player, m.key().toStack(count));
                left -= count;
            }
        }
        if (gain.getTotalMoney() > 0) ViScriptShopServerUtil.addMoney(player, gain.getTotalMoney());
        if (gain.getTotalXp() > 0) player.giveExperiencePoints(gain.getTotalXp());
        gain.getCommands().forEach(command -> BuyMerchantPayload.executeCommands(player, command));
        NeoForge.EVENT_BUS.post(new ShopServerEvent.BuySuccess(player, event.getShopInfo(), cost, gain));
        message(player, "success");
    }

    private static boolean validateShopRules(ServerPlayer player, String shop, ShopServerEvent.BuyPre event) {
        for (var entry : event.getGainSummary().getPurchaseEntries()) {
            CategoryInfo category = event.getShopInfo().getCategoryInfos().stream()
                    .filter(c -> c.getId().equals(entry.getCategoryId())).findFirst().orElse(null);
            if (category == null) return false;
            MerchantInfo merchant = category.getMerchants().stream()
                    .filter(m -> m.getId().equals(entry.getMerchantId())).findFirst().orElse(null);
            if (merchant == null || entry.getBuyCount() <= 0) return false;
            if (!MerchantFlagGroup.canAccess(merchant.getFlagGroupMode(), merchant.getFlagGroups(),
                    ViScriptShopServerUtil.getStageFlags(player))) {
                player.sendSystemMessage(Component.translatable("viscript_shop.message.stage_flags.missing"));
                return false;
            }
            int stock = ViScriptShopServerUtil.getEffectiveMerchantStock(player, shop, category.getId(), merchant);
            if (stock >= 0 && entry.getBuyCount() > stock) {
                player.sendSystemMessage(Component.translatable("viscript_shop.message.shoppingCart.out_of_stock"));
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

    private static List<Move> planExtract(MEStorage storage, List<Need> needs, IActionSource source) {
        Map<AEItemKey, Long> planned = new LinkedHashMap<>();
        var available = storage.getAvailableStacks();
        for (Need need : needs) {
            long remaining = need.amount();
            for (var entry : available) {
                if (!(entry.getKey() instanceof AEItemKey key) || !matches(need, key.getReadOnlyStack())) continue;
                long unused = entry.getLongValue() - planned.getOrDefault(key, 0L);
                long take = Math.min(remaining, Math.max(0, unused));
                if (take > 0) { planned.merge(key, take, Long::sum); remaining -= take; }
                if (remaining == 0) break;
            }
            if (remaining != 0) return null;
        }
        List<Move> moves = planned.entrySet().stream().map(e -> new Move(e.getKey(), e.getValue())).toList();
        for (Move move : moves) if (storage.extract(move.key(), move.amount(), Actionable.SIMULATE, source) != move.amount()) return null;
        return moves;
    }

    private static List<Move> planPaymentKeys(MEStorage storage, List<Need> needs, IActionSource source) {
        Map<AEItemKey, Long> planned = new LinkedHashMap<>();
        for (Need need : needs) {
            AEItemKey key = AEItemKey.of(need.stack());
            if (key == null) return null;
            planned.merge(key, need.amount(), Long::sum);
        }
        List<Move> moves = planned.entrySet().stream().map(e -> new Move(e.getKey(), e.getValue())).toList();
        for (Move move : moves) if (storage.insert(move.key(), move.amount(), Actionable.SIMULATE, source) != move.amount()) return null;
        return moves;
    }

    private static boolean matches(Need need, ItemStack candidate) {
        return need.entry() == null ? ItemStack.isSameItemSameComponents(need.stack(), candidate)
                : need.entry().getMatchRule().matches(candidate, need.stack());
    }

    private static boolean rollback(MEStorage storage, IActionSource source, List<Move> extracted, List<Move> inserted) {
        boolean ok = true;
        for (int i = inserted.size() - 1; i >= 0; i--) {
            Move m = inserted.get(i);
            ok &= storage.extract(m.key(), m.amount(), Actionable.MODULATE, source) == m.amount();
        }
        for (int i = extracted.size() - 1; i >= 0; i--) {
            Move m = extracted.get(i);
            ok &= storage.insert(m.key(), m.amount(), Actionable.MODULATE, source) == m.amount();
        }
        return ok;
    }

    private static void message(ServerPlayer player, String suffix) {
        player.sendSystemMessage(Component.translatable("scex_viscriptshop_ae2." + suffix));
    }

    private record Need(ItemStack stack, long amount, AggregatedResources.ItemEntry entry) {}
    private record Move(AEItemKey key, long amount) {}
    private static final class TransactionFailure extends RuntimeException { TransactionFailure(String m) { super(m); } }
}
