package com.example.blueprint.client;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 给 AE2 的 ME 线缆方块画一副"示意轮廓"。
 * <p>
 * 为什么不用真模型：线缆的 {@code CableBusBakedModel} 要靠方块实体喂的渲染状态才算得出来，
 * 而蓝图预览那个位置根本没有方块实体（{@code ModelData.EMPTY} 下它直接返回空列表），
 * 于是整段线缆在投影和预览里完全不可见。真去重建渲染状态则要查世界里的邻居，
 * 蓝图里也没有这个信息。
 * <p>
 * 所以这里退一步：不追求像，只追求"看得出这儿是什么"。线缆画成一节芯加几个方向的臂，
 * 挂在面上的部件按类型换个颜色——终端一种、其它部件一种。
 * <p>
 * 判断方块靠注册名而不是类型，这样就不用引用 AE2 的类，没装 AE2 时本类也只是个空壳。
 */
public final class CableBusOutline {

    /** AE2 所有线缆与其上的部件都装在这一个方块里 */
    private static final ResourceLocation CABLE_BUS_ID = new ResourceLocation("ae2", "cable_bus");

    private static final Block CABLE_BUS = ForgeRegistries.BLOCKS.getValue(CABLE_BUS_ID);

    /** 线缆本体：青蓝 */
    private static final float[] CABLE_COLOR = {0.35F, 0.72F, 0.95F};
    /** ME 终端：绿 */
    private static final float[] TERMINAL_COLOR = {0.42F, 0.92F, 0.55F};
    /** 其余部件（总线、接口、驱动之类）：琥珀 */
    private static final float[] PART_COLOR = {0.95F, 0.80F, 0.35F};

    /** 方块自身的线框，坐标取 0~1 */
    public record Outline(AABB box, float red, float green, float blue) {
    }

    private CableBusOutline() {
    }

    public static boolean isCableBus(BlockState state) {
        return CABLE_BUS != null && state.is(CABLE_BUS);
    }

    /**
     * 该方块要画的轮廓；返回 null 表示"不是线缆方块"。
     * <p>
     * 坐标是结构内的相对坐标，和 {@link Schematic} 的取值方式一致。
     */
    @Nullable
    public static List<Outline> outlinesOf(Schematic schematic, int x, int y, int z, Rotation rotation) {
        // 调用方来自渲染循环，手上那份坐标未必和这里的结构对得上
        // （比如蓝图刚转过向），越界就直接当不是线缆处理
        if (!schematic.inBounds(x, y, z) || !isCableBus(schematic.stateAt(x, y, z))) {
            return null;
        }

        List<Outline> result = new ArrayList<>();

        // 芯：中心一小块，让人一眼认出这格里是线缆而不是普通方块
        result.add(outline(cube(0.38D, 0.62D), CABLE_COLOR));

        // 臂：伸向邻居的那几段。
        // 真正的连接方向是 CablePart 查世界里的邻居算出来的，蓝图里没有这个信息，
        // 只能用"邻居也是线缆方块"来近似——纯线缆网络里是准的，
        // 线缆接到机器（驱动器、接口）的那一段会缺一截。
        for (Direction direction : Direction.values()) {
            int nx = x + direction.getStepX();
            int ny = y + direction.getStepY();
            int nz = z + direction.getStepZ();
            if (schematic.inBounds(nx, ny, nz) && isCableBus(schematic.stateAt(nx, ny, nz))) {
                result.add(outline(arm(direction), CABLE_COLOR));
            }
        }

        // 部件：方块实体 NBT 里每个部件占一个以方向命名的键，键名就是它挂在哪个面
        CompoundTag tag = schematic.blockEntityAt(x, y, z);
        if (tag != null) {
            for (String key : tag.getAllKeys()) {
                Direction side = Direction.byName(key);
                if (side == null || !(tag.get(key) instanceof CompoundTag part)) {
                    // 键名不是方向，那就是中心的线缆本体，已经画过了
                    continue;
                }
                // 结构旋转时 BlockState 会跟着转，但方块实体 NBT 是原样搬运的，
                // 键名里记的方向还停在原朝向——所以这里得自己补上同样的旋转
                result.add(outline(partPlate(Schematic.rotateDirection(side, rotation)),
                        colorOf(part.getString("id"))));
            }
        }

        return result;
    }

    private static float[] colorOf(String partId) {
        if (partId.contains("terminal")) {
            return TERMINAL_COLOR;
        }
        return partId.contains("cable") ? CABLE_COLOR : PART_COLOR;
    }

    private static Outline outline(AABB box, float[] color) {
        return new Outline(box, color[0], color[1], color[2]);
    }

    private static AABB cube(double lo, double hi) {
        return new AABB(lo, lo, lo, hi, hi, hi);
    }

    /** 从芯伸向某个方向的短臂 */
    private static AABB arm(Direction direction) {
        return switch (direction) {
            case DOWN -> new AABB(0.38D, 0.0D, 0.38D, 0.62D, 0.38D, 0.62D);
            case UP -> new AABB(0.38D, 0.62D, 0.38D, 0.62D, 1.0D, 0.62D);
            case NORTH -> new AABB(0.38D, 0.38D, 0.0D, 0.62D, 0.62D, 0.38D);
            case SOUTH -> new AABB(0.38D, 0.38D, 0.62D, 0.62D, 0.62D, 1.0D);
            case WEST -> new AABB(0.0D, 0.38D, 0.38D, 0.38D, 0.62D, 0.62D);
            case EAST -> new AABB(0.62D, 0.38D, 0.38D, 1.0D, 0.62D, 0.62D);
        };
    }

    /** 贴在某个面上的一小块，表示"这个方向挂了部件" */
    private static AABB partPlate(Direction side) {
        double lo = 0.30D;
        double hi = 0.70D;
        double depth = 0.45D;
        return switch (side) {
            case DOWN -> new AABB(lo, 0.0D, lo, hi, depth, hi);
            case UP -> new AABB(lo, 1.0D - depth, lo, hi, 1.0D, hi);
            case NORTH -> new AABB(lo, lo, 0.0D, hi, hi, depth);
            case SOUTH -> new AABB(lo, lo, 1.0D - depth, hi, hi, 1.0D);
            case WEST -> new AABB(0.0D, lo, lo, depth, hi, hi);
            case EAST -> new AABB(1.0D - depth, lo, lo, 1.0D, hi, hi);
        };
    }
}
