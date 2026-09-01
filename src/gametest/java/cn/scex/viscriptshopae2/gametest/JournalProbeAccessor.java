package cn.scex.viscriptshopae2.gametest;

import appeng.api.storage.MEStorage;
import appeng.api.stacks.AEItemKey;
import com.viscriptshop.event.neoforge.ShopServerEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.FileChannel;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.storage.LevelResource;

final class JournalProbeAccessor {
    private static final String CLASS = "cn.scex.viscriptshopae2.TradeJournal";
    private static final Map<UUID, ServerPlayer> PLAYERS = new HashMap<>();
    private JournalProbeAccessor() {}

    static void prepare(ServerPlayer player, String shop, Object binding, ShopServerEvent.BuyPre event,
                        MEStorage storage, List<ItemStack> keys) {
        try {
            Class<?> journal = Class.forName(CLASS);
            Method prepare = journal.getDeclaredMethod("prepare", ServerPlayer.class, String.class,
                    Class.forName("cn.scex.viscriptshopae2.TerminalBinding"), ShopServerEvent.BuyPre.class,
                    MEStorage.class, List.class, List.class, List.class, Map.class);
            prepare.setAccessible(true);
            List<ItemStack> before = new java.util.ArrayList<>();
            List<ItemStack> after = new java.util.ArrayList<>();
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                before.add(player.getInventory().getItem(slot).copy());
                after.add(player.getInventory().getItem(slot).copy());
            }
            int payment = 2;
            for (int slot = 0; slot < after.size() && payment > 0; slot++) {
                ItemStack stack = after.get(slot);
                if (!stack.is(Items.IRON_INGOT)) continue;
                int debit = Math.min(payment, stack.getCount());
                stack.shrink(debit);
                payment -= debit;
            }
            if (payment != 0) throw new AssertionError("Prepared fixture has insufficient iron payment");
            boolean stacked = false;
            for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
                ItemStack stack = after.get(slot);
                if (stack.is(Items.DIAMOND) && stack.getCount() < stack.getMaxStackSize()) {
                    stack.grow(1);
                    stacked = true;
                    break;
                }
            }
            if (!stacked) {
                for (int slot = 0; slot < player.getInventory().items.size(); slot++) {
                    if (after.get(slot).isEmpty()) {
                        after.set(slot, new ItemStack(Items.DIAMOND));
                        stacked = true;
                        break;
                    }
                }
            }
            if (!stacked) throw new AssertionError("Prepared fixture has no room for diamond goods");
            Map<AEItemKey, Long> deltas = new java.util.LinkedHashMap<>();
            deltas.put(AEItemKey.of(Items.DIAMOND), -1L);
            deltas.put(AEItemKey.of(Items.IRON_INGOT), 2L);
            prepare.invoke(null, player, shop, binding, event, storage, keys, before, after, deltas);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot prepare durable journal", failure);
        }
    }

    static boolean replayFromDisk(MinecraftServer server, ServerPlayer player, String shop) {
        try {
            Path file = journalForShop(server, shop);
            CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            Class<?> journal = Class.forName(CLASS);
            Constructor<?> constructor = journal.getDeclaredConstructor(MinecraftServer.class, Path.class,
                    CompoundTag.class);
            constructor.setAccessible(true);
            Object reloaded = constructor.newInstance(server, file, data);
            Method recover = journal.getDeclaredMethod("recover", ServerPlayer.class);
            recover.setAccessible(true);
            return (boolean) recover.invoke(reloaded, player);
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Cannot replay journal from disk", failure);
        }
    }

    static void simulateNewProcess(MinecraftServer server, String shop) {
        try {
            Class<?> journal = Class.forName(CLASS);
            Field applied = journal.getDeclaredField("APPLIED_THIS_PROCESS");
            applied.setAccessible(true);
            CompoundTag data = NbtIo.readCompressed(journalForShop(server, shop), NbtAccounter.unlimitedHeap());
            ((Map<?, ?>) applied.get(null)).remove(data.getUUID("transaction"));
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Cannot reset process replay guard", failure);
        }
    }

    static void registerPlayer(ServerPlayer player) {
        PLAYERS.put(player.getUUID(), player);
    }

    static void simulateNewProcessAndReplayAll(MinecraftServer server) {
        if (!simulateNewProcessAndTryReplayAll(server)) {
            throw new AssertionError("Ordered recovery stopped before the full WAL prefix was applied");
        }
    }

    static boolean simulateNewProcessAndTryReplayAll(MinecraftServer server) {
        try {
            Class<?> journal = Class.forName(CLASS);
            Field applied = journal.getDeclaredField("APPLIED_THIS_PROCESS");
            applied.setAccessible(true);
            ((Map<?, ?>) applied.get(null)).clear();
            Method recoverAll = journal.getDeclaredMethod("recoverAll", MinecraftServer.class, Function.class);
            recoverAll.setAccessible(true);
            @SuppressWarnings("unchecked")
            Function<UUID, ServerPlayer> resolver = PLAYERS::get;
            return (boolean) recoverAll.invoke(null, server, resolver);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot simulate ordered process restart", failure);
        }
    }

    static void reverseTargetFileNames(MinecraftServer server, String shop) {
        try {
            List<Path> targets = journalsForShop(server, shop);
            for (int index = 0; index < targets.size(); index++) {
                Path source = targets.get(index);
                Path target = source.resolveSibling(String.format("%s-%03d.nbt", shop.hashCode(),
                        targets.size() - index));
                Files.move(source, target);
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot scramble WAL file names", failure);
        }
    }

    static void checkpointApplied(MinecraftServer server) {
        try {
            Class<?> journal = Class.forName(CLASS);
            Method checkpoint = journal.getDeclaredMethod("checkpointApplied", MinecraftServer.class);
            checkpoint.setAccessible(true);
            checkpoint.invoke(null, server);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot checkpoint applied test journals", failure);
        }
    }

    static JournalBackup corrupt(MinecraftServer server, String shop, String corruption) {
        try {
            Path file = journalForShop(server, shop);
            CompoundTag original = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            CompoundTag changed = original.copy();
            Path duplicate = null;
            switch (corruption) {
                case "slot_oob" -> changed.getList("slots", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        .getCompound(0).putInt("slot", 999);
                case "empty_key" -> changed.getList("network", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        .getCompound(0).put("stack", new CompoundTag());
                case "negative_amount" -> changed.getList("network", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        .getCompound(0).putLong("postAmount", -1);
                case "bad_side" -> changed.putString("side", "diagonal");
                case "bad_state" -> changed.putString("state", "UNKNOWN");
                case "bad_sequence" -> changed.putLong("sequence", 0);
                case "duplicate_slot" -> changed.getList("slots", net.minecraft.nbt.Tag.TAG_COMPOUND)
                        .add(changed.getList("slots", net.minecraft.nbt.Tag.TAG_COMPOUND).getCompound(0).copy());
                case "duplicate_sequence" -> {
                    changed.putUUID("transaction", UUID.randomUUID());
                    duplicate = file.resolveSibling(changed.getUUID("transaction") + ".nbt");
                    NbtIo.writeCompressed(changed, duplicate);
                    return new JournalBackup(file, original, duplicate);
                }
                default -> throw new IllegalArgumentException("Unknown corruption " + corruption);
            }
            NbtIo.writeCompressed(changed, file);
            return new JournalBackup(file, original, duplicate);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot corrupt WAL fixture", failure);
        }
    }

    static void restore(JournalBackup backup) {
        try {
            NbtIo.writeCompressed(backup.original(), backup.file());
            if (backup.duplicate() != null) Files.deleteIfExists(backup.duplicate());
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot restore WAL fixture", failure);
        }
    }

    static void setOldestState(MinecraftServer server, String shop, String state) {
        try {
            Path file = journalsForShop(server, shop).getFirst();
            CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            data.putString("state", state);
            NbtIo.writeCompressed(data, file);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot adjust WAL order fixture", failure);
        }
    }

    static void applyLatestPostInventory(MinecraftServer server, ServerPlayer player, String shop) {
        try {
            Path file = journalsForShop(server, shop).getLast();
            CompoundTag data = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            var slots = data.getList("slots", net.minecraft.nbt.Tag.TAG_COMPOUND);
            for (int index = 0; index < slots.size(); index++) {
                CompoundTag transition = slots.getCompound(index);
                player.getInventory().setItem(transition.getInt("slot"), ItemStack.parseOptional(
                        server.registryAccess(), transition.getCompound("post")));
            }
            player.getInventory().setChanged();
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot apply WAL post-inventory fixture", failure);
        }
    }

    static ProbeJournal latestJournal(MinecraftServer server) {
        try {
            List<Path> paths;
            try (var files = Files.list(directory(server))) {
                paths = files.filter(path -> path.toString().endsWith(".nbt")).toList();
            }
            if (paths.isEmpty()) throw new java.io.IOException("No pending crash-probe journal");
            Path latest = null;
            CompoundTag latestData = null;
            for (Path path : paths) {
                CompoundTag data = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                if (latestData == null || data.getLong("sequence") > latestData.getLong("sequence")) {
                    latest = path;
                    latestData = data;
                }
            }
            return new ProbeJournal(latest, latestData);
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot read crash-probe journal", failure);
        }
    }

    static String stateForShop(MinecraftServer server, String shop) {
        try {
            return NbtIo.readCompressed(journalsForShop(server, shop).getLast(),
                    NbtAccounter.unlimitedHeap()).getString("state");
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot inspect crash-probe journal state", failure);
        }
    }

    static void writeProbeSentinel(MinecraftServer server, String propertyName, String phase, String status) {
        try {
            String configured = System.getProperty(propertyName);
            if (configured == null || configured.isBlank()) {
                throw new java.io.IOException("Missing sentinel system property " + propertyName);
            }
            ProbeJournal journal = latestJournal(server);
            String body = "phase=" + phase + "\nstatus=" + status + "\nsequence="
                    + journal.data().getLong("sequence") + "\nstate=" + journal.data().getString("state")
                    + "\ntransaction=" + journal.data().getUUID("transaction") + "\nplayer="
                    + journal.data().getUUID("player") + "\nshop=" + journal.data().getString("shop")
                    + "\njournal=" + journal.file().toAbsolutePath() + "\n";
            Path target = Path.of(configured).toAbsolutePath();
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
            Files.writeString(temporary, body, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot write durable crash-probe sentinel", failure);
        }
    }

    static int journalCount(MinecraftServer server) {
        try {
            Path directory = directory(server);
            if (!Files.isDirectory(directory)) return 0;
            try (var files = Files.list(directory)) {
                return Math.toIntExact(files.filter(path -> path.toString().endsWith(".nbt")).count());
            }
        } catch (java.io.IOException failure) {
            throw new AssertionError("Cannot count journals", failure);
        }
    }

    static boolean hasJournal(MinecraftServer server, String shop) {
        try {
            journalForShop(server, shop);
            return true;
        } catch (java.io.IOException missing) {
            return false;
        }
    }

    private static Path journalForShop(MinecraftServer server, String shop) throws java.io.IOException {
        List<Path> journals = journalsForShop(server, shop);
        if (journals.isEmpty()) throw new java.io.IOException("No journal for shop " + shop);
        return journals.getFirst();
    }

    private static List<Path> journalsForShop(MinecraftServer server, String shop) throws java.io.IOException {
        List<Path> result = new java.util.ArrayList<>();
        try (var files = Files.list(directory(server))) {
            for (Path path : files.filter(value -> value.toString().endsWith(".nbt")).toList()) {
                CompoundTag data = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                if (data.getString("shop").equals(shop)) result.add(path);
            }
        }
        result.sort(java.util.Comparator.comparingLong(path -> {
            try {
                return NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap()).getLong("sequence");
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }));
        return result;
    }

    private static Path directory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("scex_viscriptshop_ae2_transactions");
    }

    record JournalBackup(Path file, CompoundTag original, Path duplicate) {}
    record ProbeJournal(Path file, CompoundTag data) {}
}
