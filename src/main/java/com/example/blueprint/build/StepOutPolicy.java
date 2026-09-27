package com.example.blueprint.build;

/**
 * "她该不该先站到投影外再动手"这件事的**纯逻辑**：只吃几个数，不碰世界。
 * <p>
 * 为什么把它抽出来：这条判断在 1.6.6 前后改了五回（判据从"她挡着格子"改成"在不在投影里"、
 * 站位的"家"坐标钉错、寻路节流让她一步没走、最后靠硬挪兜住），每一回都是改完进游戏看现象，
 * 因为决定的输入散在控制器十几个字段里、还没有测试。抽成纯函数之后，
 * "什么情况下该走、该挪、还是就地开工"是**能跑测试**的，而不是靠观察倒推。
 * <p>
 * 控制器只负责把事实喂进来（她在不在投影里、宽限期还剩多少、手上有没有落脚点、
 * 这一趟硬挪过没有），并执行返回的动作。
 */
public final class StepOutPolicy {

    /** 这一 tick 拿她怎么办 */
    public enum Move {
        /** 继续往外走（还在宽限期内，且有个结构外的落脚点） */
        WALK_OUT,
        /** 宽限期过了她还在里头：直接把她落到落脚点上，然后再开工 */
        HARD_MOVE,
        /** 就地开工（不在投影里、没人能给她找落脚点、或者已经挪过一次了） */
        WORK_HERE
    }

    /**
     * @param insideProjection  她此刻在不在投影的水平范围里
     * @param graceLeft         宽限期还剩多少 tick（&le;0 表示已经用完了）
     * @param hasStandSpot      手上有没有"结构外的落脚点"（没有就没得走，也没得挪）
     * @param alreadyHardMoved  这一趟已经硬挪过一次没有（挪过还不领情就不再折腾她）
     * @param allowHardMove     配置上允不允许硬挪（见 {@code BlueprintConfig}）
     */
    public static Move decide(boolean insideProjection, int graceLeft, boolean hasStandSpot,
                              boolean alreadyHardMoved, boolean allowHardMove) {
        if (!insideProjection) {
            // 不在投影里：正常开工。这一条放在最前面，别的都只是"怎么把她弄出去"
            return Move.WORK_HERE;
        }
        if (graceLeft > 0) {
            // 宽限期内专心走；没落脚点就走不了，只能就地开工（控制台那边会留一行说明）
            return hasStandSpot ? Move.WALK_OUT : Move.WORK_HERE;
        }
        if (allowHardMove && hasStandSpot && !alreadyHardMoved) {
            return Move.HARD_MOVE;
        }
        return Move.WORK_HERE;
    }

    /**
     * 走不到站位时要不要"换个落脚点再试一次"。
     * <p>
     * 只有**她还在投影里**才值得换：在外面走不到某个点，就地开工本来就没问题。
     */
    public static boolean shouldRetryElsewhere(boolean insideProjection, int triesSoFar, int maxTries) {
        return insideProjection && triesSoFar < maxTries;
    }

    private StepOutPolicy() {
    }
}
