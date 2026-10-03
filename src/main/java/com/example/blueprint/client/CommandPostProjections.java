package com.example.blueprint.client;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 客户端这边的"我附近有哪些指挥台、各自托管着什么"。
 * <p>
 * <b>数据从方块实体自己同步过来</b>，不另开网络包：指挥台的状态本来就走
 * {@code getUpdateTag} / {@code getUpdatePacket}（见 {@code CommandPostBlockEntity}），
 * 而方块实体这个类两端都有、客户端离得够近就会收到一份，于是"登记"这件事
 * 放在它 {@code load} 里做就够了——区块加载、状态变更两条路都会经过那儿。
 * 这样安排还有一个好处：**走远了方块实体被卸掉，投影自己就没了**，
 * 不必再维护"谁该看见哪一台"。
 * <p>
 * 记两样东西：**谁是主人**（在图纸库里点「投影」时要知道该放到哪一台）与
 * **托着什么**（渲染用）。结构本体不在这儿，仍然按 uid 按需向服务端点播。
 */
@OnlyIn(Dist.CLIENT)
public final class CommandPostProjections {

    /** 一份托管投影：结构 id、名字、锚点、朝向。与蓝图物品 NBT 里那几样一一对应 */
    public record Projection(UUID id, String name, BlockPos anchor, Rotation rotation, Mirror mirror) {
    }

    /** 一台指挥台在客户端这边的样子：主人 + 有没有投影 */
    public record Post(@Nullable UUID owner, @Nullable Projection projection) {
    }

    private static final Map<BlockPos, Post> POSTS = new HashMap<>();

    private CommandPostProjections() {
    }

    /** 登记 / 更新一台指挥台；两台都空（没主人也没投影）时把这一条划掉 */
    public static void update(BlockPos pos, @Nullable UUID owner, @Nullable Projection projection) {
        BlockPos key = pos.immutable();
        if (owner == null && projection == null) {
            POSTS.remove(key);
        } else {
            POSTS.put(key, new Post(owner, projection));
        }
    }

    /** 这台指挥台不在了（区块卸载、被拆掉） */
    public static void remove(BlockPos pos) {
        POSTS.remove(pos);
    }

    /** 退出世界时清干净：那份表属于"这个存档里的这几台"，换个存档就不作数了 */
    public static void clear() {
        POSTS.clear();
    }

    /**
     * 离该点最近的那一份托管投影；一份都没有时返回 null。
     * <p>
     * 取最近的而不是"全都画"：投影是半透明的，叠两张谁也看不清（与女仆那条同一个理由）。
     */
    @Nullable
    public static Projection nearest(BlockPos center) {
        Projection best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Post> entry : POSTS.entrySet()) {
            Projection projection = entry.getValue().projection();
            if (projection == null) {
                continue;
            }
            double distance = entry.getKey().distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = projection;
            }
        }
        return best;
    }

    /**
     * 离该点最近的、**属于该玩家**的指挥台在哪一格；没有就返回 null。
     * <p>
     * 从终端（而不是从某台指挥台的界面）点「投影」时，就放到这一台上——
     * 不用先跑去右键它一次。从指挥台界面进来的话，调用方会直接把自己那一格递过来。
     */
    @Nullable
    public static BlockPos nearestMine(BlockPos center, UUID player) {
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Map.Entry<BlockPos, Post> entry : POSTS.entrySet()) {
            if (!player.equals(entry.getValue().owner())) {
                continue;
            }
            double distance = entry.getKey().distSqr(center);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = entry.getKey();
            }
        }
        return best;
    }
}
