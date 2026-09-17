package com.example.blueprint;

import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.integration.ae2.Ae2TerminalRegistry;
import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.registry.ModItems;
import com.mojang.logging.LogUtils;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
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

        // AE2 的终端方块只在装了 AE2 时才注册：注册类会引用 AE2 的类型，
        // 没装的情况下触碰它就是一个 NoClassDefFoundError
        if (Ae2Compat.isLoaded()) {
            Ae2TerminalRegistry.register(modBus);
        }

        modBus.addListener(this::commonSetup);
        modBus.addListener(this::addCreative);

        MinecraftForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        ModNetwork.register();
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.BLUEPRINT);
            event.accept(ModItems.BINDING_BOOK);
            if (Ae2Compat.isLoaded()) {
                event.accept(Ae2TerminalRegistry.MAID_TERMINAL_ITEM);
            }
        }
    }
}
