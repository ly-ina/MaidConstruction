package com.example.blueprint.client;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 进度条数据源的四条守则。这一轮"条来回跳"改了三回，所以把守则钉成测试：
 * <ul>
 *   <li>同一处工地上**只往前不往回**（重扫、会话重建都会报出更小的数）；</li>
 *   <li>换了工地（site 变了）才从头算；</li>
 *   <li>记账按 **UUID**：同一个实体 id 上换过人也不会串味；</li>
 *   <li>建筑名跟着**最新**那一包走（玩家中途改名要立刻反映出来）。</li>
 * </ul>
 */
class MaidBuildProgressTest {

    private final UUID maid = UUID.randomUUID();

    @AfterEach
    void clearAll() {
        MaidBuildProgress.clear(null);
    }

    /** 没有建筑名的简版 */
    private void put(long site, int done, int total) {
        MaidBuildProgress.put(1, maid, site, "", done, total, (byte) 0);
    }

    private MaidBuildProgress.Entry only() {
        var all = MaidBuildProgress.active();
        assertEquals(1, all.size(), "应该只有一条记录");
        return all.iterator().next();
    }

    private MaidBuildProgress.Entry byUuid(UUID uuid) {
        for (MaidBuildProgress.Entry entry : MaidBuildProgress.active()) {
            if (entry.maidUuid().equals(uuid)) {
                return entry;
            }
        }
        throw new AssertionError("没有这位女仆的记录：" + uuid);
    }

    @Test
    @DisplayName("同一处工地：报了个更小的数，显示的还是大的那个")
    void neverGoesBackwardsOnSameSite() {
        put(7L, 100, 900);
        put(7L, 80, 900);
        assertEquals(100, only().done());
    }

    @Test
    @DisplayName("换了工地：从头算，不被上一个工地的大数压着")
    void newSiteStartsOver() {
        put(7L, 800, 900);
        put(8L, 5, 900);
        assertEquals(5, only().done());
    }

    @Test
    @DisplayName("按 UUID 记账：同一个实体 id 上的新旧两只女仆是两条记录，互不覆盖")
    void keyedByUuidNotEntityId() {
        UUID other = UUID.randomUUID();
        put(7L, 100, 900);
        MaidBuildProgress.put(1, other, 7L, "", 10, 900, (byte) 0); // 同一个实体 id，换人了
        // 两条记录各归各的（"不许串味"）：这正是 key 用 UUID 的意义——
        // 按实体 id 记的话，后一只会把前一只的记录顶掉，前一只就永远带着别人的进度
        assertEquals(2, MaidBuildProgress.active().size());
        assertEquals(100, byUuid(maid).done());
        assertEquals(10, byUuid(other).done());
    }

    @Test
    @DisplayName("建筑名跟着最新那一包走：中途改名立刻反映")
    void nameFollowsLatestPacket() {
        MaidBuildProgress.put(1, maid, 7L, "旧砖厂", 100, 900, (byte) 0);
        assertEquals("旧砖厂", only().name());
        MaidBuildProgress.put(1, maid, 7L, "新砖厂", 120, 900, (byte) 0);
        assertEquals("新砖厂", only().name());
        // 没起名就是空串——由 HUD 翻成"未命名建筑"，包和服务端都不掺和翻译
        MaidBuildProgress.put(1, maid, 7L, "", 130, 900, (byte) 0);
        assertEquals("", only().name());
    }

    @Test
    @DisplayName("剩余块数不会为负，百分比封顶 100")
    void leftAndPercentAreClamped() {
        // done 是数世界得来的，比图纸记的还多是有可能的
        put(7L, 950, 900);
        MaidBuildProgress.Entry entry = only();
        assertEquals(0, entry.left());
        assertEquals(100, entry.percent());
    }

    @Test
    @DisplayName("正常情况下：百分比按四舍五入")
    void percentRounds() {
        put(7L, 1, 3);
        assertEquals(33, only().percent());
    }
}
