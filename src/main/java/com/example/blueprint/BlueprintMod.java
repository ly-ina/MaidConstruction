package com.example.blueprint;

import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.integration.ae2.Ae2TerminalRegistry;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.registry.ModCreativeTabs;
import com.example.blueprint.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(BlueprintMod.MOD_ID)
public class BlueprintMod {
    public static final String MOD_ID = "blueprint";
    public static final Logger LOGGER = LogUtils.getLogger();

    public BlueprintMod() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();

        ModItems.ITEMS.register(modBus);
        ModCreativeTabs.TABS.register(modBus);

        // AE2 的终端方块只在装了 AE2 时才注册：注册类会引用 AE2 的类型，
        // 没装的情况下触碰它就是一个 NoClassDefFoundError
        if (Ae2Compat.isLoaded()) {
            Ae2TerminalRegistry.register(modBus);
            // ME 线缆方块的材料要按它上面的部件算，不能走 Block.asItem()
            Ae2Compat.registerMaterialResolver();
            // 线缆上各部件的朝向记在 NBT 键名里，结构旋转时得自己搬
            Ae2Compat.registerBlockEntityRotation();
        }

        modBus.addListener(this::commonSetup);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ModNetwork.register();
        // 升级卡关联、无线访问点的链接登记都要等物品都注册完了才能做，
        // 所以放这儿而不是构造函数里。内部自带 AE2 缺失保护
        Ae2Compat.registerItemHooks();
    }
}
