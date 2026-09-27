package com.example.blueprint.build;

import net.minecraft.core.BlockPos;

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

    private StandSpotSearch() {
    }
}
