package com.example.blueprint.integration.maid;

import net.minecraftforge.fml.ModList;

/**
 * 车万女仆模组装没装。
 * <p>
 * 这个类里**一句女仆模组的类型都没有**，只有一个 {@link ModList} 查询——正是这一点
 * 让它可以在"女仆模组没装"的客户端里被放心调用。
 * <p>
 * 为什么非要这么绕：客户端那两个渲染器（投影 {@code ProjectionRenderer}、
 * 挡路高亮 {@code BlockedSpotHighlighter}）是普通的事件订阅者，每帧都要跑一遍，
 * 女仆模组没装也照样跑。它们的方法体里只要出现一次 {@code EntityMaid} 这个名字，
 * 没装女仆的客户端执行到那儿就是一次 {@code NoClassDefFoundError}——
 * 整局游戏当场崩掉（1.6.3 的客户端崩溃就是这么来的）。
 * <p>
 * 所以规矩是：<b>能每帧跑的地方只准问 {@link #isLoaded()}</b>，
 * 真正的女仆代码关在另一个类里（客户端那边是 {@code MaidClientBridge}），
 * 只有确认装了才会被加载。
 */
public final class MaidCompat {

    /** 车万女仆的模组 id */
    public static final String MOD_ID = "touhou_little_maid";

    private static Boolean loaded;

    public static boolean isLoaded() {
        if (loaded == null) {
            loaded = ModList.get().isLoaded(MOD_ID);
        }
        return loaded;
    }

    private MaidCompat() {
    }
}
