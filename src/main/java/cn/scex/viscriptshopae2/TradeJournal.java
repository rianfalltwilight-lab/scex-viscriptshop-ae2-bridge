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
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

/** Durable write-ahead snapshot for one bridged trade. */
final class TradeJournal {
    private static final int FORMAT = 4;
    private static final String DIRECTORY = "scex_viscriptshop_ae2_transactions";
    private static final String SEQUENCE_FILE = "sequence.dat";
    private static final Map<UUID, Path> APPLIED_THIS_PROCESS = new ConcurrentHashMap<>();
    private static volatile boolean recoveryPending;

    private final MinecraftServer server;
    private final Path file;
    private final CompoundTag data;

    private TradeJournal(MinecraftServer server, Path file, CompoundTag data) {
        this.server = server;
        this.file = file;
        this.data = data;
    }

    static TradeJournal prepare(ServerPlayer player, String shop, ConnectorBinding binding,
                                ShopServerEvent.BuyPre event, MEStorage storage,
                                List<ItemStack> affectedKeys, List<ItemStack> inventoryBefore,
                                List<ItemStack> inventoryAfter, Map<AEItemKey, Long> networkDeltas) {
        UUID id = UUID.randomUUID();
        CompoundTag root = new CompoundTag();
        root.putInt("format", FORMAT);
        root.putUUID("transaction", id);
        root.putLong("sequence", allocateSequence(player.server));
        root.putLong("createdTick", player.server.getTickCount());
        root.putString("state", "PREPARED");
        root.putUUID("player", player.getUUID());
        root.putString("shop", shop);
        root.putString("dimension", binding.dimension().location().toString());
        root.putLong("connector", binding.pos().asLong());
        root.putInt("money", ViScriptShopServerUtil.getMoney(player));
        root.putInt("postMoney", Math.addExact(Math.subtractExact(ViScriptShopServerUtil.getMoney(player),
                event.getCostSummary().getTotalMoney()), event.getGainSummary().getTotalMoney()));
        root.putInt("xpTotal", player.totalExperience);
        root.putInt("xpLevel", player.experienceLevel);
        root.putFloat("xpProgress", player.experienceProgress);
        root.putInt("postXpTotal", Math.addExact(Math.subtractExact(player.totalExperience,
                event.getCostSummary().getTotalXp()), event.getGainSummary().getTotalXp()));

        ListTag slots = new ListTag();
        for (int slot = 0; slot < inventoryBefore.size(); slot++) {
            if (ItemStack.matches(inventoryBefore.get(slot), inventoryAfter.get(slot))) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", slot);
            entry.put("pre", inventoryBefore.get(slot).saveOptional(player.server.registryAccess()));
            entry.put("post", inventoryAfter.get(slot).saveOptional(player.server.registryAccess()));
            slots.add(entry);
        }
        root.put("slots", slots);

        Map<AEItemKey, ItemStack> unique = new LinkedHashMap<>();
        for (ItemStack stack : affectedKeys) {
            AEItemKey key = AEItemKey.of(stack);
            if (key != null) unique.putIfAbsent(key, stack.copyWithCount(1));
        }
        IActionSource source = IActionSource.ofPlayer(player);
        ListTag network = snapshotNetwork(player.server, storage, source, unique.values());
        for (CompoundTag entry : compounds(network)) {
            AEItemKey key = AEItemKey.of(ItemStack.parseOptional(player.server.registryAccess(),
                    entry.getCompound("stack")));
            long post = entry.getLong("amount") + networkDeltas.getOrDefault(key, 0L);
            if (post < 0) throw new IllegalStateException("Negative planned ME state");
            entry.putLong("postAmount", post);
        }
        root.put("network", network);

        ShopSavedData shops = ViscriptShop.getShopSavedData();
        String owner = ViScriptShopServerUtil.isPersonalStockEnabled()
                ? player.getUUID().toString() : ShopSavedData.GLOBAL_STOCK_OWNER;
        ListTag stocks = new ListTag();
        Map<StockKey, Integer> stockDeltas = new LinkedHashMap<>();
        for (var purchase : event.getGainSummary().getPurchaseEntries()) {
            var category = event.getShopInfo().getCategoryInfos().stream()
                    .filter(value -> value.getId().equals(purchase.getCategoryId())).findFirst().orElseThrow();
            var merchant = category.getMerchants().stream()
                    .filter(value -> value.getId().equals(purchase.getMerchantId())).findFirst().orElseThrow();
            stockDeltas.merge(new StockKey(shop, owner, category.getId(), merchant.getId()),
                    purchase.getBuyCount(), Math::addExact);
        }
        for (var delta : stockDeltas.entrySet()) {
            StockKey key = delta.getKey();
            var merchant = event.getShopInfo().getCategoryInfos().stream()
                    .filter(value -> value.getId().equals(key.category())).findFirst().orElseThrow()
                    .getMerchants().stream().filter(value -> value.getId().equals(key.merchant())).findFirst().orElseThrow();
            int before = shops.getMerchantStock(shop, owner, key.category(), key.merchant(), merchant.getStock());
            CompoundTag stock = new CompoundTag();
            stock.putString("owner", owner);
            stock.putString("category", key.category());
            stock.putString("merchant", key.merchant());
            stock.putInt("amount", before);
            stock.putInt("postAmount", before < 0 ? before
                    : Math.max(0, Math.subtractExact(before, delta.getValue())));
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
        for (CompoundTag entry : compounds(data.getList("slots", Tag.TAG_COMPOUND))) {
            ItemStack expected = ItemStack.parseOptional(server.registryAccess(), entry.getCompound("post"));
            if (!ItemStack.matches(player.getInventory().getItem(entry.getInt("slot")), expected)) {
                throw new IllegalStateException("Inventory post-state mismatch");
            }
        }
        IActionSource source = IActionSource.ofPlayer(player);
        for (CompoundTag entry : compounds(data.getList("network", Tag.TAG_COMPOUND))) {
            AEItemKey key = AEItemKey.of(ItemStack.parseOptional(server.registryAccess(), entry.getCompound("stack")));
            long current = storage.extract(key, Long.MAX_VALUE, Actionable.SIMULATE, source);
            if (current != entry.getLong("postAmount")) throw new IllegalStateException("ME post-state mismatch");
        }
        if (ViScriptShopServerUtil.getMoney(player) != data.getInt("postMoney")
                || player.totalExperience != data.getInt("postXpTotal")) {
            throw new IllegalStateException("Economy post-state mismatch");
        }
        data.putInt("postXpLevel", player.experienceLevel);
        data.putFloat("postXpProgress", player.experienceProgress);
        ShopSavedData shops = ViscriptShop.getShopSavedData();
        for (CompoundTag entry : compounds(data.getList("stocks", Tag.TAG_COMPOUND))) {
            int current = shops.getMerchantStock(data.getString("shop"), entry.getString("owner"),
                    entry.getString("category"), entry.getString("merchant"), entry.getInt("amount"));
            if (current != entry.getInt("postAmount")) throw new IllegalStateException("Stock post-state mismatch");
        }
        data.putString("state", "COMMITTED");
        persist();
        APPLIED_THIS_PROCESS.put(data.getUUID("transaction"), file);
    }

    static synchronized boolean recoverAll(MinecraftServer server) {
        return recoverAll(server, id -> server.getPlayerList().getPlayer(id));
    }

    static synchronized boolean recoverAll(MinecraftServer server, Function<UUID, ServerPlayer> playerResolver) {
        try {
            return recoverAllChecked(server, playerResolver);
        } catch (Throwable failure) {
            recoveryPending = true;
            ScexViScriptShopAe2.LOGGER.error("Unexpected WAL recovery failure; failing closed", failure);
            return false;
        }
    }

    private static boolean recoverAllChecked(MinecraftServer server,
                                             Function<UUID, ServerPlayer> playerResolver) {
        Path directory = directory(server);
        if (!Files.isDirectory(directory)) {
            recoveryPending = false;
            return true;
        }
        List<TradeJournal> journals = new ArrayList<>();
        try (var files = Files.list(directory)) {
            long allocatedSequence = readAllocatedSequence(directory);
            for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".nbt")).toList()) {
                try {
                    CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                    validate(server, file, root, allocatedSequence);
                    journals.add(new TradeJournal(server, file, root));
                } catch (Exception failure) {
                    ScexViScriptShopAe2.LOGGER.error("Cannot recover transaction journal {}", file, failure);
                    recoveryPending = true;
                    return false;
                }
            }
        } catch (IOException failure) {
            ScexViScriptShopAe2.LOGGER.error("Cannot scan transaction journals in {}", directory, failure);
            recoveryPending = true;
            return false;
        }
        journals.sort(Comparator.comparingLong(journal -> journal.data.getLong("sequence")));
        Set<Long> sequences = new HashSet<>();
        Set<UUID> transactions = new HashSet<>();
        for (TradeJournal journal : journals) {
            long sequence = journal.data.getLong("sequence");
            UUID transaction = journal.data.getUUID("transaction");
            if (!sequences.add(sequence) || !transactions.add(transaction)) {
                ScexViScriptShopAe2.LOGGER.error("Duplicate WAL identity sequence={} transaction={}",
                        sequence, transaction);
                recoveryPending = true;
                return false;
            }
        }
        List<TradeJournal> pending = journals.stream().filter(journal ->
                !APPLIED_THIS_PROCESS.containsKey(journal.data.getUUID("transaction"))).toList();
        List<TradeJournal> prepared = pending.stream()
                .filter(journal -> journal.data.getString("state").equals("PREPARED")).toList();
        if (prepared.size() > 1 || (prepared.size() == 1 && prepared.getFirst() != pending.getLast())) {
            ScexViScriptShopAe2.LOGGER.error("Invalid WAL order: PREPARED must be the unique sequence tail");
            logPending(journals);
            recoveryPending = true;
            return false;
        }
        boolean recovered = true;
        if (prepared.size() == 1) {
            TradeJournal tail = prepared.getFirst();
            recovered = recoverBatch(server, List.of(tail), playerResolver, true);
            if (recovered) {
                tail.data.putString("state", "ROLLED_BACK");
                tail.persist();
                APPLIED_THIS_PROCESS.put(tail.data.getUUID("transaction"), tail.file);
            }
        }
        if (recovered) recovered = recoverBatch(server, journals, playerResolver, false);
        if (!recovered) logPending(journals);
        recoveryPending = !recovered;
        return recovered;
    }

    static boolean ensureReady(MinecraftServer server) {
        return !recoveryPending || recoverAll(server);
    }

    static void markRecoveryPending() {
        recoveryPending = true;
    }

    boolean recover(ServerPlayer playerOverride) {
        try {
            boolean recovered = recoverBatch(server, List.of(this), id -> playerOverride != null
                    && playerOverride.getUUID().equals(id) ? playerOverride : server.getPlayerList().getPlayer(id), true);
            if (recovered && !data.getString("state").equals("COMMITTED")) {
                data.putString("state", "ROLLED_BACK");
                persist();
                APPLIED_THIS_PROCESS.put(data.getUUID("transaction"), file);
            }
            return recovered;
        } catch (Exception failure) {
            ScexViScriptShopAe2.LOGGER.error("Recovery failed for transaction {}", data.getUUID("transaction"), failure);
            return false;
        }
    }

    boolean rollbackInProcess(ServerPlayer player) {
        data.putString("state", "PREPARED");
        APPLIED_THIS_PROCESS.remove(data.getUUID("transaction"));
        return recover(player);
    }

    private static boolean recoverBatch(MinecraftServer server, List<TradeJournal> input,
                                        Function<UUID, ServerPlayer> playerResolver, boolean allowMutation) {
        List<TradeJournal> journals = input.stream()
                .filter(journal -> !APPLIED_THIS_PROCESS.containsKey(journal.data.getUUID("transaction")))
                .sorted(Comparator.comparingLong(journal -> journal.data.getLong("sequence"))).toList();
        if (journals.isEmpty()) return true;

        Map<SlotKey, StackAccumulator> slots = new LinkedHashMap<>();
        Map<NetworkKey, ScalarAccumulator<Long>> network = new LinkedHashMap<>();
        Map<StockKey, ScalarAccumulator<Integer>> stocks = new LinkedHashMap<>();
        Map<UUID, ScalarAccumulator<Integer>> money = new LinkedHashMap<>();
        Map<UUID, XpAccumulator> experience = new LinkedHashMap<>();
        Map<UUID, ServerPlayer> players = new LinkedHashMap<>();

        for (TradeJournal journal : journals) {
            CompoundTag root = journal.data;
            UUID playerId = root.getUUID("player");
            boolean committed = root.getString("state").equals("COMMITTED");
            ServerPlayer player = playerResolver.apply(playerId);
            if (player == null) {
                ScexViScriptShopAe2.LOGGER.error("Cannot recover WAL sequence={}: player {} is unavailable",
                        root.getLong("sequence"), playerId);
                return false;
            }
            players.put(playerId, player);
            for (CompoundTag entry : compounds(root.getList("slots", Tag.TAG_COMPOUND))) {
                ItemStack pre = ItemStack.parseOptional(server.registryAccess(), entry.getCompound("pre"));
                ItemStack post = ItemStack.parseOptional(server.registryAccess(), entry.getCompound("post"));
                slots.computeIfAbsent(new SlotKey(playerId, entry.getInt("slot")), ignored -> new StackAccumulator())
                        .add(pre, post, committed ? post : pre);
            }
            ConnectorBinding binding = journal.binding();
            for (CompoundTag entry : compounds(root.getList("network", Tag.TAG_COMPOUND))) {
                AEItemKey key = AEItemKey.of(ItemStack.parseOptional(server.registryAccess(), entry.getCompound("stack")));
                if (key == null) continue;
                network.computeIfAbsent(new NetworkKey(binding, key), ignored -> new ScalarAccumulator<>())
                        .add(entry.getLong("amount"), entry.getLong("postAmount"),
                                committed ? entry.getLong("postAmount") : entry.getLong("amount"));
            }
            for (CompoundTag entry : compounds(root.getList("stocks", Tag.TAG_COMPOUND))) {
                StockKey key = new StockKey(root.getString("shop"), entry.getString("owner"),
                        entry.getString("category"), entry.getString("merchant"));
                stocks.computeIfAbsent(key, ignored -> new ScalarAccumulator<>()).add(entry.getInt("amount"),
                        entry.getInt("postAmount"), committed ? entry.getInt("postAmount") : entry.getInt("amount"));
            }
            money.computeIfAbsent(playerId, ignored -> new ScalarAccumulator<>()).add(root.getInt("money"),
                    root.getInt("postMoney"), committed ? root.getInt("postMoney") : root.getInt("money"));
            experience.computeIfAbsent(playerId, ignored -> new XpAccumulator()).add(root, committed);
        }

        Map<ConnectorBinding, MEStorage> storages = new LinkedHashMap<>();
        for (NetworkKey key : network.keySet()) {
            var resolved = key.binding().resolve(server);
            if (resolved.isEmpty()) {
                ScexViScriptShopAe2.LOGGER.error("Cannot recover WAL: ME shop connector is unavailable binding={}",
                        key.binding() + " reason=" + key.binding().unavailableReason(server));
                return false;
            }
            storages.putIfAbsent(key.binding(), resolved.get().storage());
        }
        IActionSource source = IActionSource.empty();
        for (var entry : network.entrySet()) {
            long current = storages.get(entry.getKey().binding()).extract(entry.getKey().key(), Long.MAX_VALUE,
                    Actionable.SIMULATE, source);
            long minimum = entry.getValue().allowed.stream().mapToLong(Long::longValue).min().orElseThrow();
            long maximum = entry.getValue().allowed.stream().mapToLong(Long::longValue).max().orElseThrow();
            if (allowMutation ? current < minimum || current > maximum : current != entry.getValue().target) {
                return conflict("ME", entry.getKey(), current);
            }
        }
        ShopSavedData shopData = ViscriptShop.getShopSavedData();
        for (var entry : stocks.entrySet()) {
            StockKey key = entry.getKey();
            int current = shopData.getMerchantStock(key.shop(), key.owner(), key.category(), key.merchant(),
                    entry.getValue().target);
            if (allowMutation ? !entry.getValue().allowed.contains(current) : current != entry.getValue().target) {
                return conflict("stock", key, current);
            }
        }
        for (var entry : slots.entrySet()) {
            ItemStack current = players.get(entry.getKey().player()).getInventory().getItem(entry.getKey().slot());
            if (allowMutation ? !entry.getValue().allows(current) : !ItemStack.matches(current, entry.getValue().target)) {
                return conflict("slot", entry.getKey(), current);
            }
        }
        for (var entry : money.entrySet()) {
            int current = ViScriptShopServerUtil.getMoney(players.get(entry.getKey()));
            if (allowMutation ? !entry.getValue().allowed.contains(current) : current != entry.getValue().target) {
                return conflict("money", entry.getKey(), current);
            }
        }
        for (var entry : experience.entrySet()) {
            int current = players.get(entry.getKey()).totalExperience;
            if (allowMutation ? !entry.getValue().allowedTotals.contains(current) : current != entry.getValue().target.total()) {
                return conflict("xp", entry.getKey(), current);
            }
        }

        if (allowMutation) {
            for (var entry : network.entrySet()) {
                MEStorage storage = storages.get(entry.getKey().binding());
                long current = storage.extract(entry.getKey().key(), Long.MAX_VALUE, Actionable.SIMULATE, source);
                long target = entry.getValue().target;
                long done = current > target
                        ? storage.extract(entry.getKey().key(), current - target, Actionable.MODULATE, source)
                        : storage.insert(entry.getKey().key(), target - current, Actionable.MODULATE, source);
                if (done != Math.abs(current - target)) return false;
            }
            for (var entry : stocks.entrySet()) {
                StockKey key = entry.getKey();
                shopData.setMerchantStock(key.shop(), key.owner(), key.category(), key.merchant(), entry.getValue().target);
            }
            shopData.setDirty();
            for (var entry : slots.entrySet()) {
                players.get(entry.getKey().player()).getInventory().setItem(entry.getKey().slot(),
                        entry.getValue().target.copy());
            }
            players.values().forEach(player -> player.getInventory().setChanged());
            money.forEach((id, value) -> ViScriptShopServerUtil.setMoney(players.get(id), value.target));
            experience.forEach((id, value) -> value.apply(players.get(id)));
        }
        for (TradeJournal journal : journals) {
            UUID transaction = journal.data.getUUID("transaction");
            APPLIED_THIS_PROCESS.put(transaction, journal.file);
            ScexViScriptShopAe2.LOGGER.warn("Replayed ordered sequence={} state={} transaction={}",
                    journal.data.getLong("sequence"), journal.data.getString("state"), transaction);
        }
        return true;
    }

    private static boolean conflict(String resource, Object key, Object current) {
        ScexViScriptShopAe2.LOGGER.error("WAL compare-and-set conflict resource={} key={} current={}; failing closed",
                resource, key, current);
        return false;
    }

    private static void logPending(List<TradeJournal> journals) {
        for (TradeJournal journal : journals) {
            if (APPLIED_THIS_PROCESS.containsKey(journal.data.getUUID("transaction"))) continue;
            ScexViScriptShopAe2.LOGGER.error(
                    "Pending WAL sequence={} state={} transaction={} player={} shop={} file={}",
                    journal.data.getLong("sequence"), journal.data.getString("state"),
                    journal.data.getUUID("transaction"), journal.data.getUUID("player"),
                    journal.data.getString("shop"), journal.file);
        }
    }

    static synchronized void checkpointApplied(MinecraftServer server) {
        Path directory = directory(server);
        for (var applied : List.copyOf(APPLIED_THIS_PROCESS.entrySet())) {
            UUID transaction = applied.getKey();
            try {
                deleteDurably(applied.getValue());
                APPLIED_THIS_PROCESS.remove(transaction);
            } catch (IOException failure) {
                ScexViScriptShopAe2.LOGGER.error("Cannot checkpoint transaction {}", transaction, failure);
            }
        }
    }

    private ConnectorBinding binding() {
        ResourceKey<Level> dimension = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                ResourceLocation.parse(data.getString("dimension")));
        return new ConnectorBinding(data.getUUID("player"), dimension, BlockPos.of(data.getLong("connector")));
    }

    private void persist() {
        try {
            Files.createDirectories(file.getParent());
            writeTagDurably(data, file);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot persist transaction journal " + file, failure);
        }
    }

    private static void deleteDurably(Path file) throws IOException {
        if (!Files.exists(file)) return;
        Path tombstone = file.resolveSibling(file.getFileName() + ".checkpointed");
        moveAtomically(file, tombstone);
        forceDirectory(file.getParent());
        Files.deleteIfExists(tombstone);
        forceDirectory(file.getParent());
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

    private static synchronized long allocateSequence(MinecraftServer server) {
        Path directory = directory(server);
        Path sequenceFile = directory.resolve(SEQUENCE_FILE);
        try {
            Files.createDirectories(directory);
            long last = 0;
            if (Files.exists(sequenceFile)) {
                CompoundTag sequence = NbtIo.readCompressed(sequenceFile, NbtAccounter.unlimitedHeap());
                if (sequence.getInt("format") != FORMAT || !sequence.contains("last", Tag.TAG_LONG)) {
                    throw new IOException("Invalid WAL sequence file");
                }
                last = sequence.getLong("last");
            } else {
                try (var files = Files.list(directory)) {
                    if (files.anyMatch(path -> path.getFileName().toString().endsWith(".nbt"))) {
                        throw new IOException("Sequence file missing while transaction journals exist");
                    }
                }
            }
            long next = Math.incrementExact(last);
            if (next <= 0) throw new IOException("WAL sequence exhausted");
            CompoundTag sequence = new CompoundTag();
            sequence.putInt("format", FORMAT);
            sequence.putLong("last", next);
            writeTagDurably(sequence, sequenceFile);
            return next;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot allocate durable WAL sequence", failure);
        }
    }

    private static void validate(MinecraftServer server, Path file, CompoundTag root, long allocatedSequence)
            throws IOException {
        if (root.getInt("format") != FORMAT) throw new IOException("Unsupported WAL format");
        if (!root.hasUUID("transaction") || root.getLong("sequence") <= 0
                || root.getLong("sequence") > allocatedSequence || !root.hasUUID("player")) {
            throw new IOException("Missing WAL identity");
        }
        if (root.getString("shop").isBlank()) throw new IOException("Missing shop id");
        String state = root.getString("state");
        if (!state.equals("PREPARED") && !state.equals("COMMITTED") && !state.equals("ROLLED_BACK")) {
            throw new IOException("Invalid WAL state");
        }
        validateCompoundList(root, "slots");
        validateCompoundList(root, "network");
        validateCompoundList(root, "stocks");
        if (!root.contains("connector", Tag.TAG_LONG) || !root.contains("createdTick", Tag.TAG_LONG)
                || !root.contains("money", Tag.TAG_INT) || !root.contains("postMoney", Tag.TAG_INT)
                || !root.contains("xpTotal", Tag.TAG_INT) || !root.contains("xpLevel", Tag.TAG_INT)
                || !root.contains("xpProgress", Tag.TAG_FLOAT) || !root.contains("postXpTotal", Tag.TAG_INT)) {
            throw new IOException("Incomplete scalar snapshot");
        }
        if (root.getInt("money") < 0 || root.getInt("postMoney") < 0
                || root.getInt("xpTotal") < 0 || root.getInt("xpLevel") < 0 || root.getInt("postXpTotal") < 0
                || !Float.isFinite(root.getFloat("xpProgress")) || root.getFloat("xpProgress") < 0
                || root.getFloat("xpProgress") >= 1) throw new IOException("Invalid XP snapshot");
        ResourceLocation.parse(root.getString("dimension"));
        Set<Integer> slotKeys = new HashSet<>();
        for (CompoundTag entry : compounds(root.getList("slots", Tag.TAG_COMPOUND))) {
            int slot = entry.getInt("slot");
            if (slot < 0 || slot > net.minecraft.world.entity.player.Inventory.SLOT_OFFHAND) {
                throw new IOException("Inventory slot out of bounds: " + slot);
            }
            if (!slotKeys.add(slot)) throw new IOException("Duplicate inventory slot transition: " + slot);
            if (!entry.contains("pre", Tag.TAG_COMPOUND) || !entry.contains("post", Tag.TAG_COMPOUND)) {
                throw new IOException("Incomplete slot transition");
            }
            ItemStack.parseOptional(server.registryAccess(), entry.getCompound("pre"));
            ItemStack.parseOptional(server.registryAccess(), entry.getCompound("post"));
        }
        validateStacks(server, root.getList("network", Tag.TAG_COMPOUND), true);
        Set<AEItemKey> networkKeys = new HashSet<>();
        for (CompoundTag entry : compounds(root.getList("network", Tag.TAG_COMPOUND))) {
            if (!entry.contains("postAmount", Tag.TAG_LONG) || entry.getLong("amount") < 0
                    || entry.getLong("postAmount") < 0) throw new IOException("Invalid ME amounts");
            ItemStack stack = ItemStack.parseOptional(server.registryAccess(), entry.getCompound("stack"));
            AEItemKey key = AEItemKey.of(stack);
            if (stack.isEmpty() || key == null) throw new IOException("Empty ME key");
            if (!networkKeys.add(key)) throw new IOException("Duplicate ME key transition");
        }
        Set<StockKey> stockKeys = new HashSet<>();
        for (CompoundTag entry : compounds(root.getList("stocks", Tag.TAG_COMPOUND))) {
            if (!entry.contains("amount", Tag.TAG_INT) || !entry.contains("postAmount", Tag.TAG_INT)) {
                throw new IOException("Incomplete stock transition");
            }
            if (entry.getString("owner").isBlank() || entry.getString("category").isBlank()
                    || entry.getString("merchant").isBlank()) throw new IOException("Empty stock key");
            int pre = entry.getInt("amount");
            int post = entry.getInt("postAmount");
            if ((pre < 0 && post != pre) || (pre >= 0 && (post < 0 || post > pre))) {
                throw new IOException("Invalid stock transition");
            }
            StockKey key = new StockKey(root.getString("shop"), entry.getString("owner"),
                    entry.getString("category"), entry.getString("merchant"));
            if (!stockKeys.add(key)) throw new IOException("Duplicate stock transition");
        }
        if (state.equals("COMMITTED") && (!root.contains("postXpLevel", Tag.TAG_INT)
                || !root.contains("postXpProgress", Tag.TAG_FLOAT))) {
            throw new IOException("Incomplete COMMITTED XP snapshot");
        }
        if (state.equals("COMMITTED") && (root.getInt("postXpLevel") < 0
                || !Float.isFinite(root.getFloat("postXpProgress")) || root.getFloat("postXpProgress") < 0
                || root.getFloat("postXpProgress") >= 1)) throw new IOException("Invalid COMMITTED XP snapshot");
    }

    private static void validateCompoundList(CompoundTag root, String key) throws IOException {
        if (!root.contains(key, Tag.TAG_LIST)) throw new IOException("Missing WAL list: " + key);
        ListTag list = root.getList(key, Tag.TAG_COMPOUND);
        if (!list.isEmpty() && list.getElementType() != Tag.TAG_COMPOUND) {
            throw new IOException("Invalid WAL list element type: " + key);
        }
    }

    private static void validateStacks(MinecraftServer server, ListTag entries, boolean amounts) throws IOException {
        for (CompoundTag entry : compounds(entries)) {
            if (!entry.contains("stack", Tag.TAG_COMPOUND)) throw new IOException("Missing stack snapshot");
            ItemStack.parseOptional(server.registryAccess(), entry.getCompound("stack"));
            if (amounts && entry.getLong("amount") < 0) throw new IOException("Negative ME amount");
        }
    }

    private static long readAllocatedSequence(Path directory) throws IOException {
        Path sequenceFile = directory.resolve(SEQUENCE_FILE);
        if (!Files.exists(sequenceFile)) throw new IOException("Missing WAL sequence file");
        CompoundTag sequence = NbtIo.readCompressed(sequenceFile, NbtAccounter.unlimitedHeap());
        if (sequence.getInt("format") != FORMAT || sequence.getLong("last") <= 0) {
            throw new IOException("Invalid WAL sequence file");
        }
        return sequence.getLong("last");
    }

    private static void writeTagDurably(CompoundTag tag, Path file) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        NbtIo.writeCompressed(tag, temporary);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        moveAtomically(temporary, file);
        forceDirectory(file.getParent());
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void forceDirectory(Path directory) {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException unsupported) {
            // Windows providers commonly reject opening directories. The file itself was fsynced
            // before an atomic same-directory rename; directory force is best-effort in Java NIO.
            ScexViScriptShopAe2.LOGGER.debug("Directory fsync unavailable for {}: {}", directory,
                    unsupported.toString());
        }
    }

    private record SlotKey(UUID player, int slot) {}
    private record NetworkKey(ConnectorBinding binding, AEItemKey key) {}
    private record StockKey(String shop, String owner, String category, String merchant) {}
    private record XpState(int total, int level, float progress) {}

    private static final class ScalarAccumulator<T> {
        private final Set<T> allowed = new HashSet<>();
        private T target;
        private void add(T pre, T post, T selected) {
            allowed.add(pre);
            allowed.add(post);
            target = selected;
        }
    }

    private static final class StackAccumulator {
        private final List<ItemStack> allowed = new ArrayList<>();
        private ItemStack target = ItemStack.EMPTY;
        private void add(ItemStack pre, ItemStack post, ItemStack selected) {
            allowed.add(pre.copy());
            allowed.add(post.copy());
            target = selected.copy();
        }
        private boolean allows(ItemStack stack) {
            return allowed.stream().anyMatch(candidate -> ItemStack.matches(candidate, stack));
        }
    }

    private static final class XpAccumulator {
        private final Set<Integer> allowedTotals = new HashSet<>();
        private XpState target;
        private void add(CompoundTag root, boolean committed) {
            XpState pre = new XpState(root.getInt("xpTotal"), root.getInt("xpLevel"), root.getFloat("xpProgress"));
            XpState post = new XpState(root.getInt("postXpTotal"), root.getInt("postXpLevel"),
                    root.getFloat("postXpProgress"));
            allowedTotals.add(pre.total());
            allowedTotals.add(post.total());
            target = committed ? post : pre;
        }
        private void apply(ServerPlayer player) {
            player.totalExperience = target.total();
            player.experienceLevel = target.level();
            player.experienceProgress = target.progress();
        }
    }
}
