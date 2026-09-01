package cn.scex.viscriptshopae2;

public final class BridgeContext {
    private static final ThreadLocal<String> SHOP = new ThreadLocal<>();
    private BridgeContext() {}
    public static void enter(String shop) { SHOP.set(shop); }
    public static String shop() { return SHOP.get(); }
    public static void exit() { SHOP.remove(); }
}
