package com.example.blueprint.client;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端记着"哪位女仆建到哪了、此刻在干什么"，给进度条当数据源。
 * <p>
 * 数据是服务端推来的（{@code S2CBuildProgressPacket}），只在施工期间推、而且节流；
 * 超过 {@link #STALE_MS} 没有新进度就当这条没用了。
 * <p>
 * <b>键是她的 UUID，不是实体 id。</b>实体 id 会被游戏复用（区块卸载重载、女仆移除再放出来
 * 都会换 id，旧 id 还可能被别的实体拿走）——按 id 记账时，一条已经没人更新的旧进度
 * 会一直挂在屏幕上跟真身抢位置。按 UUID 记账之后，"同一个人的新进度"永远覆盖旧的，
 * 这类幽灵记录从根上不存在。实体 id 只当"去哪儿找她本人"的线索用，
 * 每一包都会刷新（见 {@link Entry#maidId()}）。
 * <p>
 * <b>进度只往前不往回。</b>服务端把进度算得很认真（会话重建时有 fastForward 顶着，
 * 见 {@code BuildSession}），但那终究是"这一刻世界的扫描结果"，会因为重扫、方块被拆、
 * 会话重建而抖动。进度条是给人看的：同一处工地上只认更大的那个数，
 * 换工地（{@link Entry#site()} 变了）才从头开始。这样条永远不会"跳回去"。
 */
public final class MaidBuildProgress {

    /** 超过这么久没有新进度就作废（约两秒：比推的节拍长得多，够撑过丢包） */
    private static final long STALE_MS = 2000L;

    private static final Map<UUID, Entry> ENTRIES = new LinkedHashMap<>();

    private MaidBuildProgress() {
    }

    /**
     * 收到一条进度。
     *
     * @param site 工地身份：同一处工地（同一张图 + 同一个锚点 + 朝向）每次重扫都一样，
     *             换个位置重新开工就是另一个值
     */
    public static void put(int maidId, UUID maidUuid, long site, int done, int total, byte phase) {
        Entry old = ENTRIES.get(maidUuid);
        // 同一处工地：认更大的那个数（进度不回退）；换了工地：从这一包重新算
        int shown = old != null && old.site() == site ? Math.max(old.done(), done) : done;
        ENTRIES.put(maidUuid,
                new Entry(maidUuid, maidId, site, shown, total, phase, System.currentTimeMillis()));
    }

    /**
     * 现在还作数的那些进度，顺手把过期的清掉。
     *
     * @return 记录（调用方只读，别改）
     */
    public static Collection<Entry> active() {
        long now = System.currentTimeMillis();
        ENTRIES.entrySet().removeIf(entry -> now - entry.getValue().at() > STALE_MS);
        return ENTRIES.values();
    }

    /** 全清（换存档、断线时用） */
    public static void clear(@Nullable UUID maidUuid) {
        if (maidUuid == null) {
            ENTRIES.clear();
        } else {
            ENTRIES.remove(maidUuid);
        }
    }

    /** 一次施工的快照：是谁（UUID + 去哪找她）、建在哪一处、已完成多少、一共多少、此刻在干什么 */
    public record Entry(UUID maidUuid, int maidId, long site, int done, int total, byte phase, long at) {

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
}
