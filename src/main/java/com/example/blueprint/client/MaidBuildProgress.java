package com.example.blueprint.client;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;

/**
 * 客户端记着"哪个女仆建到哪了"，给进度条当数据源。
 * <p>
 * 数据是服务端推来的（{@code S2CBuildProgressPacket}），只在施工期间推、而且节流。
 * 所以这里也**按时效作废**：超过 {@link #STALE_MS} 没有新进度就当这条没用了——
 * 女仆停工、蓝图被收走、她走出推送半径，进度条都会自己消失，
 * 服务端不必再补一条"结束了"。
 * <p>
 * 键是**实体 id** 而不是 UUID：客户端要拿它去 {@code level.getEntity(id)} 找到她本人
 * （要按"离玩家多远"决定显不显示），实体 id 正好是干这个的。
 */
public final class MaidBuildProgress {

    /** 超过这么久没有新进度就作废（约两秒：比她一站就是几秒的节奏短，但够撑过丢包） */
    private static final long STALE_MS = 2000L;

    private static final Map<Integer, Entry> ENTRIES = new HashMap<>();

    private MaidBuildProgress() {
    }

    public static void put(int maidId, int done, int total) {
        ENTRIES.put(maidId, new Entry(done, total, System.currentTimeMillis()));
    }

    /**
     * 现在还作数的那些进度，顺手把过期的清掉。
     *
     * @return 女仆实体 id → 进度（调用方只读，别改）
     */
    public static Map<Integer, Entry> active() {
        long now = System.currentTimeMillis();
        ENTRIES.entrySet().removeIf(entry -> now - entry.getValue().at() > STALE_MS);
        return ENTRIES;
    }

    /** 一次施工的快照：已完成多少、一共多少 */
    public record Entry(int done, int total, long at) {

        /** 还剩多少块（不会小于 0） */
        public int left() {
            return Math.max(0, total - done);
        }

        /** 完成度百分比 */
        public int percent() {
            return total <= 0 ? 0 : (int) Math.round(done * 100.0D / total);
        }
    }

    /** 全清（换存档、断线时用） */
    public static void clear(@Nullable Integer maidId) {
        if (maidId == null) {
            ENTRIES.clear();
        } else {
            ENTRIES.remove(maidId);
        }
    }
}
