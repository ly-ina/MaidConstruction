package com.example.blueprint.client;

import com.example.blueprint.network.ModNetwork;
import com.example.blueprint.network.packet.C2SMaidListRequestPacket;
import com.example.blueprint.network.packet.S2CMaidListPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;

/**
 * 客户端这边的"我的女仆"名册：指挥台界面与蓝图终端共用同一份，所以两处看到的名册永远一致。
 * <p>
 * 数据**按需向服务端要**（客户端本来就不该知道"我有哪些女仆"），要的时候顺手带上
 * "已指派到哪台"：界面显示与按按钮都在这一份数据上做，不会出现名单与状态对不上的中间态。
 * <p>
 * 请求自带节流：界面每帧调一次也没关系——它只是"如果太久没要过，就要一次"。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidRoster {

    /** 同一秒内反复要没有意义：名单不会变得那么快 */
    private static final long REQUEST_INTERVAL_MS = 1000L;

    private static List<S2CMaidListPacket.Entry> entries = List.of();
    private static long lastRequestAt;

    private MaidRoster() {
    }

    /** 界面每帧叫一次即可：距上次超过 {@link #REQUEST_INTERVAL_MS} 才真的发一次包 */
    public static void request() {
        long now = System.currentTimeMillis();
        if (now - lastRequestAt < REQUEST_INTERVAL_MS) {
            return;
        }
        lastRequestAt = now;
        ModNetwork.CHANNEL.sendToServer(C2SMaidListRequestPacket.INSTANCE);
    }

    public static List<S2CMaidListPacket.Entry> entries() {
        return entries;
    }

    public static void accept(List<S2CMaidListPacket.Entry> list) {
        entries = List.copyOf(list);
    }

    /**
     * 她此刻的工作状态（名单里没有她时按"空闲"算）。
     * <p>
     * 状态**由服务端算**（见 {@code C2SMaidListRequestPacket#statusOf}）：名册里可能有别处的
     * 指挥台，那台的方块实体客户端根本没同步过来。
     */
    public static S2CMaidListPacket.Status statusOf(UUID maid) {
        for (S2CMaidListPacket.Entry entry : entries) {
            if (entry.id().equals(maid)) {
                return entry.status();
            }
        }
        return S2CMaidListPacket.Status.FREE;
    }

    /** 状态文案：空闲 / 待命 / 正在建（女仆卡片与终端主页共用同一套，两处不会写岔） */
    public static Component statusText(S2CMaidListPacket.Status status) {
        return Component.translatable(switch (status) {
            case FREE -> "gui.blueprint.maids.free";
            case STANDBY -> "gui.blueprint.maids.standby";
            case BUILDING -> "gui.blueprint.maids.building";
        });
    }

    /** 状态色：空闲灰、待命黄、在建绿 */
    public static int statusColor(S2CMaidListPacket.Status status) {
        return switch (status) {
            case FREE -> 0xAAAAAA;
            case STANDBY -> 0xFFAA00;
            case BUILDING -> 0x55FF55;
        };
    }

    /** 名单里有几只处于这个状态（终端主页那一行汇总用） */
    public static int count(S2CMaidListPacket.Status status) {
        int total = 0;
        for (S2CMaidListPacket.Entry entry : entries) {
            if (entry.status() == status) {
                total++;
            }
        }
        return total;
    }

    /** 她被指派到哪台；没指派、或那台在别的维度时返回 null */
    @Nullable
    public static BlockPos assignedPost(UUID maid) {
        for (S2CMaidListPacket.Entry entry : entries) {
            if (entry.id().equals(maid)) {
                return entry.post();
            }
        }
        return null;
    }

    /** 她在不在名单里（名单只含"我的女仆"，所以这也等于"是不是我的"） */
    public static boolean isMine(UUID maid) {
        for (S2CMaidListPacket.Entry entry : entries) {
            if (entry.id().equals(maid)) {
                return true;
            }
        }
        return false;
    }

    public static void clear() {
        entries = List.of();
    }
}
