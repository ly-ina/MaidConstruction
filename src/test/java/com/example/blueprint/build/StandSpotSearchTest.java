package com.example.blueprint.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 找落脚点的**顺序**规则。这条逻辑这一轮改错两次（判错就变成"她站在投影里原地开工"），
 * 所以把顺序钉成测试：离她最近的圈先找、同圈里同层的先看、她脚下那一格不算候选、
 * 范围之外不给（免得指到悬崖下面）。
 */
class StandSpotSearchTest {

    /** 只有集合里的格子算"能站" */
    private static BlockPos find(int baseY, Set<BlockPos> allowed) {
        return StandSpotSearch.firstAcceptable(0, baseY, 0, allowed::contains);
    }

    @Test
    @DisplayName("先给离她最近的那一圈")
    void nearestRingWins() {
        assertEquals(new BlockPos(1, 10, 0),
                find(10, Set.of(new BlockPos(1, 10, 0), new BlockPos(3, 10, 0))));
    }

    @Test
    @DisplayName("同一根柱子上：同层优先，其次才往下")
    void prefersSameLevel() {
        assertEquals(new BlockPos(1, 8, 0),
                find(10, Set.of(new BlockPos(1, 8, 0), new BlockPos(1, 7, 0))));
    }

    @Test
    @DisplayName("她自己脚下那一格不是候选——那正是要离开的地方")
    void ownCellIsNotACandidate() {
        assertNull(StandSpotSearch.firstAcceptable(0, 10, 0,
                pos -> pos.equals(new BlockPos(0, 10, 0))));
    }

    @Test
    @DisplayName("一个能站的都没有：返回 null，由调用方决定就地开工")
    void noneAcceptable() {
        assertNull(find(10, Set.of()));
    }

    @Test
    @DisplayName("离结构要留出空档：贴着墙站不合格，再往外一格才合格")
    void requiresAGapFromTheStructure() {
        // 结构占 x 0..3、z 0..3
        BlockPos anchor = new BlockPos(0, 64, 0);
        Vec3i size = new Vec3i(4, 3, 4);

        assertFalse(StandSpotSearch.clearOf(new BlockPos(-1, 64, 1), anchor, size, 2),
                "贴着西墙那一格（x = -1）会压住结构那一列，不合格");
        assertTrue(StandSpotSearch.clearOf(new BlockPos(-2, 64, 1), anchor, size, 2),
                "再往外一格（x = -2）中间正好空一格");
        assertFalse(StandSpotSearch.clearOf(new BlockPos(1, 64, 1), anchor, size, 2),
                "结构里面当然更不合格");
        assertFalse(StandSpotSearch.clearOf(new BlockPos(1, 64, 4), anchor, size, 2),
                "南北同理：z = 4 贴着");
        assertTrue(StandSpotSearch.clearOf(new BlockPos(1, 64, 5), anchor, size, 2),
                "z = 5 合格");
        assertTrue(StandSpotSearch.clearOf(new BlockPos(-2, 64, 1), anchor, size, 2),
                "站在长墙侧面时，另一根轴在墙的范围内不影响判定");
        assertTrue(StandSpotSearch.clearOf(new BlockPos(-1, 64, 1), anchor, size, 1),
                "margin = 1 就是允许贴边站——判据要按参数走，别写死");
    }

    @Test
    @DisplayName("往上超过 3 层、往下超过 6 层都不给")
    void outsideVerticalRange() {
        assertNull(StandSpotSearch.firstAcceptable(0, 10, 0, pos -> pos.getY() == 14));
        assertNull(StandSpotSearch.firstAcceptable(0, 10, 0, pos -> pos.getY() == 3));
    }

    @Test
    @DisplayName("范围之内（+3 / -6 的边界）要给")
    void verticalRangeEdgesAreIncluded() {
        // 谓词必须只认"那一格"：只写 pos.getY() == 13 的话，同一圈上先遍历到的
        // (-1,13,-1) 也会合格，断言的就不是边界了
        assertEquals(new BlockPos(1, 13, 0), StandSpotSearch.firstAcceptable(0, 10, 0,
                pos -> pos.equals(new BlockPos(1, 13, 0))));
        assertEquals(new BlockPos(1, 4, 0), StandSpotSearch.firstAcceptable(0, 10, 0,
                pos -> pos.equals(new BlockPos(1, 4, 0))));
    }
}
