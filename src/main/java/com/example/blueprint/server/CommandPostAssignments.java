package com.example.blueprint.server;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * "这只女仆被哪台指挥台指派了"——施工侧每 tick 都要问一句，所以单独摆一张表。
 * <p>
 * 为什么不每次去翻方块实体：女仆可能离指挥台很远，把附近所有指挥台扫一遍不值当；
 * 而这条关系本来就是**一对一**的（DEVELOPER §12：一只女仆同一时间只绑一台指挥台，
 * 这是"她该建哪处工地"必须唯一的前提）。
 * <p>
 * 表本身**不进存档**：真相在指挥台的方块实体里（它记着指派了哪些女仆）。
 * 表只是那份真相的索引，指挥台一加载（{@code load}）就重建一条、卸载或被拆就划掉——
 * 服务端重启后由方块实体自己把它们重新填回来，不必另存一份可能对不上的副本。
 */
public final class CommandPostAssignments {

    /** 女仆 → 她效力的那台指挥台（带维度：投影在哪个世界就建在哪个世界） */
    private static final Map<UUID, GlobalPos> POSTS = new HashMap<>();

    private CommandPostAssignments() {
    }

    public static void assign(UUID maid, GlobalPos post) {
        POSTS.put(maid, post);
    }

    public static void unassign(UUID maid) {
        POSTS.remove(maid);
    }

    /** 这台指挥台要撤单了（取消投影、被拆、区块卸载）：挂在上面的女仆全撤下来 */
    public static void clearPost(GlobalPos post) {
        POSTS.values().removeIf(post::equals);
    }

    /**
     * 该女仆效力的指挥台在哪一格；没被指派、或者那台在别的维度时返回 null。
     * <p>
     * 跨维度按"没指派"处理：她现在在哪儿就该建哪儿的东西，跑去另一个世界施工只会卡在加载上。
     */
    @Nullable
    public static BlockPos postPosIn(ServerLevel level, UUID maid) {
        GlobalPos post = POSTS.get(maid);
        if (post == null) {
            return null;
        }
        return post.dimension().equals(level.dimension()) ? post.pos() : null;
    }

    /** 这台指挥台（方块实体卸载/被拆时用） */
    public static GlobalPos at(Level level, BlockPos pos) {
        return GlobalPos.of(level.dimension(), pos.immutable());
    }
}
