package com.example.blueprint.client;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端记着"哪个女仆建到哪了、此刻在干什么"，给进度条当数据源。
 * <p>
 * 数据是服务端推来的（{@code S2CBuildProgressPacket}），只在施工期间推、而且节流。
 * 所以这里也**按时效作废**：超过 {@link #STALE_MS} 没有新进度就当这条没用了。
 * <p>
 * 键是**实体 id**（客户端要拿它 {@code level.getEntity(id)} 找到她本人、算离玩家多远），
 * 但**每条里都存着她的 UUID**：实体 id 会被游戏复用，光按 id 认人会留下一条过期的进度，
 * 看着就是"两条进度条来回覆盖"。渲染时拿 UUID 一比对，不是同一个人就当场删掉
 * （见 {@link #removeIfNot(UUID)} 的用法）。
 */
public final class MaidBuildProgress {

    /** 超过这么久没有新进度就作废（约两秒：比她一站就是几秒的节奏短，但够撑过丢包） */
    private static final long STALE_MS = 2000L;

    private static final Map<Integer, Entry> ENTRIES = new HashMap<>();

    private MaidBuildProgress() {
    }

    public static void put(int maidId, UUID maidUuid, int done, int total, byte phase) {
        ENTRIES.put(maidId, new Entry(maidUuid, done, total, phase, System.currentTimeMillis()));
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

    /**
     * 这个实体 id 上的记录**不是**这个 UUID 的（实体 id 被复用了），当场删掉。
     * <p>
     * 少了这一步，旧记录会一直挂到超时：那两秒里屏幕上就有两条，数字来回覆盖。
     */
    public static void removeIfNot(int maidId, UUID expected) {
        Entry entry = ENTRIES.get(maidId);
        if (entry != null && !entry.maidUuid().equals(expected)) {
            ENTRIES.remove(maidId);
        }
    }

    /** 实体查不到了（她被卸载、被移除）：立刻删，别等超时 */
    public static void remove(int maidId) {
        ENTRIES.remove(maidId);
    }

    /** 一次施工的快照：是谁、已完成多少、一共多少、此刻在干什么 */
    public record Entry(UUID maidUuid, int done, int total, byte phase, long at) {

        /** 还剩多少块（不会小于 0） */
        public int left() {
            return Math.max(0, total - done);
        }

        /** 完成度百分比 */
        public int percent() {
            return total <= 0 ? 0 : (int) Math.round(done * 100.0D / total);
        }

        /**
         * "她在干什么"的翻译键。包、客户端都存序号，文案在客户端按玩家语言翻——
         * 服务端要是把中文写进包，玩家切成英文也还是中文。
         */
        public String phaseKey() {
            return switch (phase) {
                case 1 -> "hud.blueprint.maid_phase.fetch";
                case 2 -> "hud.blueprint.maid_phase.walk";
                case 3 -> "hud.blueprint.maid_phase.stuck";
                default -> "hud.blueprint.maid_phase.build";
            };
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
