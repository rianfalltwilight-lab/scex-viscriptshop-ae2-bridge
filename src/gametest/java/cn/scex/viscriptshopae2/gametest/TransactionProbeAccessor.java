package cn.scex.viscriptshopae2.gametest;

import cn.scex.viscriptshopae2.AtomicTradeHandler;
import java.lang.reflect.Field;
import java.util.Map;

final class TransactionProbeAccessor {
    private TransactionProbeAccessor() {}

    static void beforeCommit(String shop, Runnable action) {
        try {
            Field field = AtomicTradeHandler.class.getDeclaredField("beforeCommitProbes");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Runnable> probes = (Map<String, Runnable>) field.get(null);
            probes.put(shop, action);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Cannot install transaction probe", failure);
        }
    }
}
