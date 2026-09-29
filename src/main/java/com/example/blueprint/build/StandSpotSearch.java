package com.example.blueprint.build;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;

import javax.annotation.Nullable;
import java.util.function.Predicate;

/**
 * 找"她能站、又不碍事"的落脚点：**从近到远**一圈圈往外找。
 * <p>
 * 为什么把这段从控制器里抽出来：站位判据这一轮反复改错（判错一次就变成"她站在投影里原地开工"），
 * 而"按什么顺序找、什么算合格"全是纯逻辑。抽成一个不依赖世界的函数之后就能单测：
 * "合格"由调用方给的 {@code acceptable} 决定（在不在结构里、站不站得住，那些要世界），
 * 这里只管**顺序**——离她最近的一圈先找，同一圈里同层的先看、再看下面。
 */
public final class StandSpotSearch {

    /** 从脚下往外最多找几圈。给得宽：结构外面常常挤满机器，得往外多找几圈 */
    public static final int RADIUS = 16;
    /** 往上找几层（她可能在结构里的高台上） */
    public static final int UP = 3;
    /** 往下找几层（结构外面往往是更低的平地） */
    public static final int DOWN = 6;
    /**
     * 同一根柱子上先看哪一层：**同层 → 上面 → 下面**，越远越靠后。
     * <p>
     * 写死成一张表而不是用两层循环拼，就是为了让"同层优先"这件事一眼可见。
     * 从最上面开始扫（{@code dy = UP} 往下）看着也行，其实不行：结构外那侧的
     * 高处要是恰好站得住（墙头、旁边的坡顶），她会为了那一格去爬墙——爬不上去，
     * 于是那趟就"走不到"，而旁边同层的平地明明迈一步就能站。
     */
    private static final int[] LEVELS = buildLevels();

    private static int[] buildLevels() {
        int[] out = new int[UP + DOWN + 1];
        out[0] = 0;
        int next = 1;
        for (int step = 1; step <= Math.max(UP, DOWN); step++) {
            if (step <= UP) {
                out[next++] = step;
            }
            if (step <= DOWN) {
                out[next++] = -step;
            }
        }
        return out;
    }

    /**
     * @param acceptable 这一格能不能用（世界相关的判断由调用方给）
     * @return 找到的第一个（也就是离她最近的）落脚点；一个都不合格时 {@code null}
     */
    @Nullable
    public static BlockPos firstAcceptable(int baseX, int baseY, int baseZ, Predicate<BlockPos> acceptable) {
        for (int radius = 1; radius <= RADIUS; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue; // 只看当前这一圈：近的先找到就先给
                    }
                    for (int dy : LEVELS) {
                        BlockPos candidate = new BlockPos(baseX + dx, baseY + dy, baseZ + dz);
                        if (acceptable.test(candidate)) {
                            return candidate;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * 这一格离结构够不够远：结构边界往外让出 {@code margin} 格才算合格。
     * <p>
     * 为什么不能只满足于"不在结构里"：紧贴着墙站，她的碰撞箱会压进结构那一列，
     * 而那一列的方块会被判成"她占着"跳过（见 {@code BuildSession#step}），
     * 于是她钉在墙边，墙永远补不完——玩家看到的就是"她挡着建造"。
     * 中间留出一格空档（{@code margin = 2}）就没这回事。
     * <p>
     * 判据是"至少有一根轴离得够远"，与 {@code BlueprintBuildController} 取结构外圈的
     * 做法一致：站在长墙侧面时，另一根轴本来就在墙的范围内。
     *
     * @param pos    落脚点（只取水平坐标，高度不参与）
     * @param anchor 结构锚点：按定义是最小角
     * @param size   结构尺寸
     * @param margin 离结构边界的最小格数：1 表示可以贴边站，2 表示中间空一格
     */
    public static boolean clearOf(BlockPos pos, BlockPos anchor, Vec3i size, int margin) {
        return pos.getX() <= anchor.getX() - margin
                || pos.getX() >= anchor.getX() + size.getX() - 1 + margin
                || pos.getZ() <= anchor.getZ() - margin
                || pos.getZ() >= anchor.getZ() + size.getZ() - 1 + margin;
    }

    private StandSpotSearch() {
    }
}
