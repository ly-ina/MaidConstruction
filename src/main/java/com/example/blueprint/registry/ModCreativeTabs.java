package com.example.blueprint.registry;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.integration.ae2.Ae2TerminalRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/**
 * 模组专属的创造物品栏。
 * <p>
 * 不塞进原版的「工具与实用物品」——那个标签页有好几页内容，蓝图和绑定书混在里面
 * 得翻半天。单独开一栏，模组的物品一眼就能找齐。
 */
public final class ModCreativeTabs {

    public static final DeferredRegister<CreativeModeTab> TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, BlueprintMod.MOD_ID);

    public static final RegistryObject<CreativeModeTab> MAIN = TABS.register("main",
            () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.blueprint.main"))
                    .icon(() -> ModItems.BLUEPRINT.get().getDefaultInstance())
                    .displayItems((params, output) -> {
                        output.accept(ModItems.BLUEPRINT.get());
                        output.accept(ModItems.BINDING_BOOK.get());
                        // 终端物品只在 AE2 存在时才有注册对象：访问这个字段会初始化
                        // Ae2TerminalRegistry 的静态字段，未装 AE2 时那是个 NoClassDefFoundError
                        if (Ae2Compat.isLoaded()) {
                            output.accept(Ae2TerminalRegistry.MAID_TERMINAL_ITEM.get());
                            output.accept(Ae2TerminalRegistry.CREATIVE_MAID_INTERFACE_ITEM.get());
                        }
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
