package com.example.blueprint.registry;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.item.BindingBookItem;
import com.example.blueprint.item.BlueprintTerminalItem;
import net.minecraft.world.item.BlockItem;
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

    /**
     * 蓝图终端：翻图纸目录用（缩略图、材料清单、把某一份取到手上）。
     * <p>
     * 与蓝图分成两件物品：蓝图是"手上正在录/正在建的那张"，终端看的是整个目录。
     * 不堆叠——它只是个开关，没有各自的 NBT。
     */
    public static final RegistryObject<Item> BLUEPRINT_TERMINAL = ITEMS.register("blueprint_terminal",
            () -> new BlueprintTerminalItem(new Item.Properties().stacksTo(1)));

    /**
     * 指挥台的方块物品。
     * <p>
     * 方块本身注册在 {@link ModBlocks}（那一位不依赖任何别的模组），物品这儿只是它的壳。
     */
    public static final RegistryObject<Item> COMMAND_POST = ITEMS.register("command_post",
            () -> new BlockItem(ModBlocks.COMMAND_POST.get(), new Item.Properties()));
}
