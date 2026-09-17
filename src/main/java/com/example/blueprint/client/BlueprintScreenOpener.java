package com.example.blueprint.client;

import com.example.blueprint.client.gui.BlueprintScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 打开蓝图面板的客户端入口。
 * <p>
 * 单独放一个类是有原因的：BlueprintItem 在服务端也会加载，
 * 如果直接在里面 new BlueprintScreen，服务端会因为找不到客户端类而崩。
 * 这里配合 DistExecutor 用，保证只有客户端才会去加载它。
 */
@OnlyIn(Dist.CLIENT)
public class BlueprintScreenOpener {

    public static void open(ItemStack stack) {
        Minecraft.getInstance().setScreen(new BlueprintScreen(stack));
    }
}
