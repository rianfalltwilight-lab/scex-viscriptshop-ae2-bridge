package cn.scex.viscriptshopae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.viscriptshop.ViscriptShop;
import com.viscriptshop.event.neoforge.ShopServerEvent;
import com.viscriptshop.gui.data.ShopSavedData;
import com.viscriptshop.util.ViScriptShopServerUtil;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** Durable write-ahead snapshot for one bridged trade. */
final class TradeJournal {
    private static final int FORMAT = 1;
    private static final String DIRECTORY = "scex_viscriptshop_ae2_transactions";
    private static final Set<UUID> APPLIED_THIS_PROCESS = ConcurrentHashMap.newKeySet();
    private static volatile boolean recoveryPending;

    private final MinecraftServer server;
    private final Path file;
    private final CompoundTag data;

    private TradeJournal(MinecraftServer server, Path file, CompoundTag data) {
        this.server = server;
        this.file = file;
        this.data = data;
    }

    static TradeJournal prepare(ServerPlayer player, String shop, TerminalBinding binding,
                                ShopServerEvent.BuyPre event, MEStorage storage,
                                List<ItemStack> affectedKeys) {
        UUID id = UUID.randomUUID();
        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT);
        root.putUUID("transaction", id);
        root.putString("state", "PREPARED");
        root.putUUID("player", player.getUUID());
        root.putString("shop", shop);
        root.putString("dimension", binding.dimension().location().toString());
        root.putLong("terminal", binding.pos().asLong());
        root.putString("side", binding.side().getName());
        root.putInt("money", ViScriptShopServerUtil.getMoney(player));
        root.putInt("xpTotal", player.totalExperience);
        root.putInt("xpLevel", player.experienceLevel);
        root.putFloat("xpProgress", player.experienceProgress);

        root.put("inventory", snapshotInventory(player));

        Map<AEItemKey, ItemStack> unique = new LinkedHashMap<>();
        for (ItemStack stack : affectedKeys) {
            AEItemKey key = AEItemKey.of(stack);
            if (key != null) unique.putIfAbsent(key, stack.copyWithCount(1));
        }
        IActionSource source = IActionSource.ofPlayer(player);
        root.put("network", snapshotNetwork(player.server, storage, source, unique.values()));

        ShopSavedData shops = ViscriptShop.getShopSavedData();
        String owner = ViScriptShopServerUtil.isPersonalStockEnabled()
                ? player.getUUID().toString() : ShopSavedData.GLOBAL_STOCK_OWNER;
        ListTag stocks = new ListTag();
        for (var purchase : event.getGainSummary().getPurchaseEntries()) {
            var category = event.getShopInfo().getCategoryInfos().stream()
                    .filter(value -> value.getId().equals(purchase.getCategoryId())).findFirst().orElseThrow();
            var merchant = category.getMerchants().stream()
                    .filter(value -> value.getId().equals(purchase.getMerchantId())).findFirst().orElseThrow();
            CompoundTag stock = new CompoundTag();
            stock.putString("owner", owner);
            stock.putString("category", category.getId());
            stock.putString("merchant", merchant.getId());
            stock.putInt("amount", shops.getMerchantStock(shop, owner, category.getId(), merchant.getId(),
                    merchant.getStock()));
            stocks.add(stock);
        }
        root.put("stocks", stocks);

        Path directory = directory(player.server);
        Path file = directory.resolve(id + ".nbt");
        TradeJournal journal = new TradeJournal(player.server, file, root);
        journal.persist();
        return journal;
    }

    void commit(ServerPlayer player, MEStorage storage) {
        data.put("postInventory", snapshotInventory(player));
        List<ItemStack> keys = new ArrayList<>();
        for (CompoundTag entry : compounds(data.getList("network", CompoundTag.TAG_COMPOUND))) {
            keys.add(ItemStack.parseOptional(server.registryAccess(), entry.getCompound("stack")));
        }
        data.put("postNetwork", snapshotNetwork(server, storage, IActionSource.ofPlayer(player), keys));
        data.putInt("postMoney", ViScriptShopServerUtil.getMoney(player));
        data.putInt("postXpTotal", player.totalExperience);
        data.putInt("postXpLevel", player.experienceLevel);
        data.putFloat("postXpProgress", player.experienceProgress);
        ShopSavedData shops = ViscriptShop.getShopSavedData();
        ListTag postStocks = new ListTag();
        for (CompoundTag before : compounds(data.getList("stocks", CompoundTag.TAG_COMPOUND))) {
            CompoundTag after = before.copy();
            after.putInt("amount", shops.getMerchantStock(data.getString("shop"), before.getString("owner"),
                    before.getString("category"), before.getString("merchant"), before.getInt("amount")));
            postStocks.add(after);
        }
        data.put("postStocks", postStocks);
        data.putString("state", "COMMITTED");
        persist();
        APPLIED_THIS_PROCESS.add(data.getUUID("transaction"));
    }

    static synchronized boolean recoverAll(MinecraftServer server) {
        Path directory = directory(server);
        if (!Files.isDirectory(directory)) {
            recoveryPending = false;
            return true;
        }
        boolean complete = true;
        try (var files = Files.list(directory)) {
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".nbt")).toList()) {
                try {
                    CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                    complete &= new TradeJournal(server, file, root).recover(null);
                } catch (Exception failure) {
                    complete = false;
                    ScexViScriptShopAe2.LOGGER.error("Cannot recover transaction journal {}", file, failure);
                }
            }
        } catch (IOException failure) {
            ScexViScriptShopAe2.LOGGER.error("Cannot scan transaction journals in {}", directory, failure);
            recoveryPending = true;
            return false;
        }
        recoveryPending = !complete;
        return complete;
    }

    static boolean ensureReady(MinecraftServer server) {
        return !recoveryPending || recoverAll(server);
    }

    static void markRecoveryPending() {
        recoveryPending = true;
    }

    boolean recover(ServerPlayer playerOverride) {
        try {
            UUID transaction = data.getUUID("transaction");
            if (APPLIED_THIS_PROCESS.contains(transaction)) return true;
            boolean committed = data.getString("state").equals("COMMITTED");
            String networkKey = committed ? "postNetwork" : "network";
            String stocksKey = committed ? "postStocks" : "stocks";
            String inventoryKey = committed ? "postInventory" : "inventory";

            TerminalBinding binding = binding();
            var terminal = binding.resolve(server);
            if (terminal.isEmpty()) return false;
            MEStorage storage = terminal.get().terminal().getInventory();
            IActionSource source = IActionSource.empty();
            for (CompoundTag entry : compounds(data.getList(networkKey, CompoundTag.TAG_COMPOUND))) {
                ItemStack stack = ItemStack.parseOptional(server.registryAccess(), entry.getCompound("stack"));
                AEItemKey key = AEItemKey.of(stack);
                if (key == null) continue;
                long target = entry.getLong("amount");
                long current = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, source);
                long done = current > target
                        ? storage.extract(key, current - target, Actionable.MODULATE, source)
                        : storage.insert(key, target - current, Actionable.MODULATE, source);
                if (done != Math.abs(current - target)) return false;
            }

            ShopSavedData shops = ViscriptShop.getShopSavedData();
            for (CompoundTag entry : compounds(data.getList(stocksKey, CompoundTag.TAG_COMPOUND))) {
                shops.setMerchantStock(data.getString("shop"), entry.getString("owner"),
                        entry.getString("category"), entry.getString("merchant"), entry.getInt("amount"));
            }
            shops.setDirty();

            UUID playerId = data.getUUID("player");
            ServerPlayer player = playerOverride != null && playerOverride.getUUID().equals(playerId)
                    ? playerOverride : server.getPlayerList().getPlayer(playerId);
            if (player == null) return false;
            for (CompoundTag entry : compounds(data.getList(inventoryKey, CompoundTag.TAG_COMPOUND))) {
                player.getInventory().setItem(entry.getInt("slot"),
                        ItemStack.parseOptional(server.registryAccess(), entry.getCompound("stack")));
            }
            player.getInventory().setChanged();
            ViScriptShopServerUtil.setMoney(player, data.getInt(committed ? "postMoney" : "money"));
            player.totalExperience = data.getInt(committed ? "postXpTotal" : "xpTotal");
            player.experienceLevel = data.getInt(committed ? "postXpLevel" : "xpLevel");
            player.experienceProgress = data.getFloat(committed ? "postXpProgress" : "xpProgress");
            APPLIED_THIS_PROCESS.add(transaction);
            ScexViScriptShopAe2.LOGGER.warn("Replayed {} bridged trade {} for player {}",
                    committed ? "committed" : "prepared", transaction, playerId);
            return true;
        } catch (Exception failure) {
            ScexViScriptShopAe2.LOGGER.error("Recovery failed for transaction {}", data.getUUID("transaction"), failure);
            return false;
        }
    }

    static synchronized void checkpointApplied(MinecraftServer server) {
        Path directory = directory(server);
        for (UUID transaction : List.copyOf(APPLIED_THIS_PROCESS)) {
            try {
                deleteDurably(directory.resolve(transaction + ".nbt"));
                APPLIED_THIS_PROCESS.remove(transaction);
            } catch (IOException failure) {
                ScexViScriptShopAe2.LOGGER.error("Cannot checkpoint transaction {}", transaction, failure);
            }
        }
    }

    private TerminalBinding binding() {
        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(data.getString("dimension")));
        return new TerminalBinding(data.getString("shop"), dimension, BlockPos.of(data.getLong("terminal")),
                Direction.byName(data.getString("side")));
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            NbtIo.writeCompressed(data, temporary);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist transaction journal " + file, failure);
        }
    }

    private static void deleteDurably(Path file) throws IOException {
        Files.deleteIfExists(file);
    }

    private static Path directory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve(DIRECTORY);
    }

    private static MinecraftServer server(ServerPlayer player) {
        return player.server;
    }

    private static ListTag snapshotInventory(ServerPlayer player) {
        ListTag inventory = new ListTag();
        for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", slot);
            entry.put("stack", player.getInventory().getItem(slot).saveOptional(player.server.registryAccess()));
            inventory.add(entry);
        }
        return inventory;
    }

    private static ListTag snapshotNetwork(MinecraftServer server, MEStorage storage, IActionSource source,
                                           Iterable<ItemStack> stacks) {
        ListTag network = new ListTag();
        for (ItemStack stack : stacks) {
            AEItemKey itemKey = AEItemKey.of(stack);
            if (itemKey == null) continue;
            CompoundTag key = new CompoundTag();
            key.put("stack", stack.copyWithCount(1).saveOptional(server.registryAccess()));
            key.putLong("amount", storage.extract(itemKey, Long.MAX_VALUE, Actionable.SIMULATE, source));
            network.add(key);
        }
        return network;
    }

    private static List<CompoundTag> compounds(ListTag list) {
        List<CompoundTag> result = new ArrayList<>(list.size());
        for (int index = 0; index < list.size(); index++) result.add(list.getCompound(index));
        return result;
    }
}
