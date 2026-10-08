package com.example.blueprint.client;

import com.example.blueprint.client.gui.ManualScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 打开说明书界面的客户端入口。
 * <p>
 * 和别的 opener 一样单独放一个类：调用它的那个物品在服务端也会被加载，
 * 界面类不能直接出现在那边的方法体里。
 */
@OnlyIn(Dist.CLIENT)
public class ManualScreenOpener {

    public static void open() {
        Minecraft.getInstance().setScreen(new ManualScreen());
    }

    private ManualScreenOpener() {
    }
}
