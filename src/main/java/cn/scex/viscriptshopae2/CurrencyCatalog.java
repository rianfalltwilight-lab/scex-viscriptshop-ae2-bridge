package cn.scex.viscriptshopae2;

import net.minecraft.world.item.ItemStack;

@FunctionalInterface
public interface CurrencyCatalog {
    int value(ItemStack stack);
}
