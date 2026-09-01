package cn.scex.viscriptshopae2.gametest;

import appeng.api.storage.MEStorage;
import com.viscriptshop.event.neoforge.ShopServerEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.LevelResource;

final class JournalProbeAccessor {
    private static final String CLASS = "cn.scex.viscriptshopae2.TradeJournal";
    private JournalProbeAccessor() {}

    static void prepare(ServerPlayer player, String shop, Object binding, ShopServerEvent.BuyPre event,
                        MEStorage storage, List<ItemStack> keys) {
        try {
            Class<?> journal = Class.forName(CLASS);
            Method prepare = journal.getDeclaredMethod("prepare", ServerPlayer.class, String.class,
                    Class.forName("cn.scex.viscriptshopae2.TerminalBinding"), ShopServerEvent.BuyPre.class,
                    MEStorage.class, List.class);
            prepare.setAccessible(true);
            prepare.invoke(null, player, shop, binding, event, storage, keys);
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
            ((Set<?>) applied.get(null)).remove(data.getUUID("transaction"));
        } catch (ReflectiveOperationException | java.io.IOException failure) {
            throw new AssertionError("Cannot reset process replay guard", failure);
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
        try (var files = Files.list(directory(server))) {
            for (Path path : files.filter(value -> value.toString().endsWith(".nbt")).toList()) {
                CompoundTag data = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                if (data.getString("shop").equals(shop)) return path;
            }
            throw new java.io.IOException("No journal for shop " + shop);
        }
    }

    private static Path directory(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("scex_viscriptshop_ae2_transactions");
    }
}
