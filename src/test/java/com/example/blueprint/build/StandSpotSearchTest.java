package com.example.blueprint.build;

import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
