package com.example.blueprint.registry;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.item.BindingBookItem;
import com.example.blueprint.item.BlueprintItem;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

public class ModItems {

    public static final DeferredRegister<Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, BlueprintMod.MOD_ID);

    public static final RegistryObject<Item> BLUEPRINT = ITEMS.register("blueprint",
            () -> new BlueprintItem(new Item.Properties().stacksTo(1)));

    /**
     * 绑定书：把女仆的取料目标固定到远处的容器或 ME 网络上。
     * <p>
     * 不堆叠——一本书只对应一个绑定，堆叠会让各自的 NBT 互相覆盖。
     */
    public static final RegistryObject<Item> BINDING_BOOK = ITEMS.register("binding_book",
            () -> new BindingBookItem(new Item.Properties().stacksTo(1)));
}
