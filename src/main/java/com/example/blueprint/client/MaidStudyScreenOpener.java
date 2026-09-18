package com.example.blueprint.client;

import com.example.blueprint.client.gui.MaidStudyScreen;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 打开学习池界面的客户端入口。
 * <p>
 * 和 {@link BlueprintScreenOpener} 一样单独放一个类：调用它的那个 handler
 * 在服务端也会被加载，界面类不能直接出现在那边的方法体里。
 */
@OnlyIn(Dist.CLIENT)
public class MaidStudyScreenOpener {

    public static void open(int maidEntityId) {
        Minecraft.getInstance().setScreen(new MaidStudyScreen(maidEntityId));
    }

    private MaidStudyScreenOpener() {
    }
}
