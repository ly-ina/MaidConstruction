package com.example.blueprint.build;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "先站出去再开工"的规则。
 * <p>
 * 这套规则在 1.6.6 前后改了五回、每次都是进游戏看现象，所以把每一条出口钉成测试：
 * 宽限期内走、宽限期后挪、挪过就别再折腾、没落脚点只能就地开工。
 */
class StepOutPolicyTest {

    private static final boolean IN = true;
    private static final boolean OUT = false;
    private static final boolean SPOT = true;
    private static final boolean NO_SPOT = false;
    private static final boolean MOVED = true;
    private static final boolean NOT_MOVED = false;
    private static final boolean ALLOW = true;
    private static final boolean FORBID = false;

    @Test
    @DisplayName("不在投影里：直接开工，别的不看")
    void outsideProjectionWorksHere() {
        assertEquals(StepOutPolicy.Move.WORK_HERE, StepOutPolicy.decide(OUT, 40, SPOT, NOT_MOVED, ALLOW));
        assertEquals(StepOutPolicy.Move.WORK_HERE, StepOutPolicy.decide(OUT, 0, NO_SPOT, MOVED, FORBID));
    }

    @Test
    @DisplayName("宽限期内有落脚点：往外走")
    void walksOutDuringGrace() {
        assertEquals(StepOutPolicy.Move.WALK_OUT, StepOutPolicy.decide(IN, 1, SPOT, NOT_MOVED, ALLOW));
        assertEquals(StepOutPolicy.Move.WALK_OUT, StepOutPolicy.decide(IN, 40, SPOT, MOVED, ALLOW));
    }

    @Test
    @DisplayName("宽限期内没落脚点：走不了，就地开工")
    void noSpotMeansWorkHere() {
        assertEquals(StepOutPolicy.Move.WORK_HERE, StepOutPolicy.decide(IN, 20, NO_SPOT, NOT_MOVED, ALLOW));
    }

    @Test
    @DisplayName("宽限期用完、有落脚点且没挪过：硬挪过去")
    void hardMovesAfterGrace() {
        assertEquals(StepOutPolicy.Move.HARD_MOVE, StepOutPolicy.decide(IN, 0, SPOT, NOT_MOVED, ALLOW));
        assertEquals(StepOutPolicy.Move.HARD_MOVE, StepOutPolicy.decide(IN, -5, SPOT, NOT_MOVED, ALLOW));
    }

    @Test
    @DisplayName("硬挪过一次就别再折腾她：就地开工")
    void neverHardMovesTwice() {
        assertEquals(StepOutPolicy.Move.WORK_HERE, StepOutPolicy.decide(IN, 0, SPOT, MOVED, ALLOW));
    }

    @Test
    @DisplayName("配置禁了硬挪：宽限期用完也只能就地开工")
    void configCanForbidHardMove() {
        assertEquals(StepOutPolicy.Move.WORK_HERE, StepOutPolicy.decide(IN, 0, SPOT, NOT_MOVED, FORBID));
        // 但宽限期里该走还是要走——禁的是"瞬移"，不是"往外走"
        assertEquals(StepOutPolicy.Move.WALK_OUT, StepOutPolicy.decide(IN, 10, SPOT, NOT_MOVED, FORBID));
    }

    @Test
    @DisplayName("只有还在投影里才值得换个落脚点再试")
    void retriesOnlyInsideProjection() {
        assertTrue(StepOutPolicy.shouldRetryElsewhere(IN, 0, 3));
        assertTrue(StepOutPolicy.shouldRetryElsewhere(IN, 2, 3));
        assertFalse(StepOutPolicy.shouldRetryElsewhere(IN, 3, 3));
        assertFalse(StepOutPolicy.shouldRetryElsewhere(OUT, 0, 3));
    }
}
