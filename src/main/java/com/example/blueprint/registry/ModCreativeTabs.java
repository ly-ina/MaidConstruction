package com.example.blueprint.registry;

import com.example.blueprint.BlueprintMod;
import com.example.blueprint.integration.ae2.Ae2Compat;
import com.example.blueprint.integration.ae2.Ae2TerminalRegistry;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

import java.util.List;

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
                            addAe2Items(output);
                        }
                    })
                    .build());

    /** "缺了哪些 AE2 注册项"这件事只报一次（创造栏每被构建一次都会走一遍这里） */
    private static boolean ae2Warned = false;

    /**
     * 把 AE2 那几件物品摆进创造栏。
     * <p>
     * <b>一件一件问"在不在"再摆，绝不直接 {@code .get()}。</b>
     * 整合包里第三方改写注册表是真实存在的（见 {@link Ae2TerminalRegistry#notRegistered()}），
     * 而这里一句 {@code .get()} 就是 {@code NullPointerException}——
     * <b>玩家一打开创造物品栏客户端就崩</b>，而且报错落在我们头上。
     * 少显示一件东西，总好过崩一次。
     */
    private static void addAe2Items(CreativeModeTab.Output output) {
        if (!ae2Warned) {
            List<String> missing = Ae2TerminalRegistry.notRegistered();
            if (!missing.isEmpty()) {
                ae2Warned = true;
                BlueprintMod.LOGGER.warn("这些 AE2 相关的注册项没落地，创造栏里会缺：{}（多半是别的模组改写了注册表）",
                        missing);
            }
        }
        for (RegistryObject<Item> object : Ae2TerminalRegistry.CREATIVE_ITEMS) {
            object.ifPresent(output::accept);
        }
    }

    private ModCreativeTabs() {
    }
}
