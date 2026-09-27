package com.example.blueprint;

import net.minecraft.advancements.Advancement;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import javax.annotation.Nullable;

/**
 * 发进度（advancement）的小工具。
 * <p>
 * 这几条进度的判据全是 {@code minecraft:impossible}，也就是说**只能由代码发**——
 * 用物品/事件触发器去匹配"她的女仆开始施工了"这种事，要么匹配不出来，要么写出一堆
 * 一改就废的 NBT 判据。代码发还顺带解决了"同一件事只会点亮一次"（{@code award} 幂等）。
 * <p>
 * 任何一条子进度被点亮之前都会**先把根点亮**：根没亮的话，子节点在进度界面里是隐藏的，
 * 于是"我明明建完了一座却什么都没看到"。
 */
public final class Advancements {

    /** 根进度：所有子进度的父节点，也是进度界面里那一页的入口 */
    private static final String ROOT = "root";
    /** 判据名。JSON 里那个 criteria 的键，和这里必须一致 */
    private static final String CRITERION = "granted";

    /** 拿到《女仆建筑说明书》 */
    public static final String GUIDE_BOOK = "guide_book";
    /** 让女仆拿着蓝图开始施工 */
    public static final String BUILD_START = "build_start";
    /** 把一座建筑建完 */
    public static final String BUILD_DONE = "build_done";

    /**
     * 给某个玩家发一条进度。发不出去（进度文件没打包进来、她主人不在线、单人里的假玩家……）
     * 都**安静地算了**：进度是锦上添花，不能因为它把施工这条路搞出异常。
     */
    public static void grant(@Nullable Entity player, String path) {
        if (!(player instanceof ServerPlayer server)) {
            return;
        }
        MinecraftServer minecraftServer = server.server;
        if (minecraftServer == null) {
            return;
        }
        if (!ROOT.equals(path)) {
            grantOne(server, minecraftServer, ROOT); // 先点亮根，否则子节点不显示
        }
        grantOne(server, minecraftServer, path);
    }

    private static void grantOne(ServerPlayer player, MinecraftServer server, String path) {
        Advancement advancement = server.getAdvancements()
                .getAdvancement(new ResourceLocation(BlueprintMod.MOD_ID, path));
        if (advancement == null) {
            return; // 数据包没进来（比如被别的整合包剔了）：当没这回事
        }
        player.getAdvancements().award(advancement, CRITERION);
    }

    private Advancements() {
    }
}
