package com.example.blueprint.server;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * "这位玩家有哪些女仆"——挂靠在主人名下的一张表，省得每次都要遍历世界再判断归属。
 * <p>
 * 怎么填：**玩家进游戏时扫一遍**（见 {@code MaidOwnershipTracker}，注册在女仆模组那边的扩展里，
 * 没装它就不会执行）。没进过表的只有"从没被加载过一次"的女仆——那基本只发生在"后来才装上这个模组"
 * 的时候，不进表也不影响：她的区块一旦加载，玩家下次进游戏就会把她扫进来。
 * <p>
 * 表**不进存档**：它是"这一次开服期间见过谁"的快照，而真相永远在世界里（实体的主人字段）。
 * 存一份反而会出现"名单里有人、世界里找不到她"这种两份记录打架的局面。
 */
public final class MaidOwnership {

    /** 一位主人的一只女仆：记名字与所在维度，界面要显示、传送要知道在哪个世界 */
    public record Maid(UUID id, String name, ResourceKey<Level> dimension) {
    }

    private static final Map<UUID, List<Maid>> BY_OWNER = new HashMap<>();

    private MaidOwnership() {
    }

    /**
     * 重新确定某位主人名下的女仆（进游戏时调一次，先清掉旧的，免得留下已经不在的）。
     * <p>
     * <b>存的是可变副本，不能用 {@code List.copyOf}。</b> 这张表之后会被 {@link #forget}
     * 改写，而 {@code List.copyOf} 给的是不可变列表——对着它 removeIf 会抛
     * {@code UnsupportedOperationException}。那个调用落在 {@code LivingDeathEvent} 里，
     * 也就是**女仆一死服务器就崩**（2.1.1 的崩溃报告就是这个：Ticking entity）。
     */
    public static void replace(UUID owner, List<Maid> maids) {
        BY_OWNER.put(owner, new ArrayList<>(maids));
    }

    /** 记一只新见到的女仆：整份换掉，不假设表里那份可不可变 */
    public static void remember(Maid maid, UUID owner) {
        List<Maid> list = new ArrayList<>(BY_OWNER.getOrDefault(owner, List.of()));
        list.add(maid);
        BY_OWNER.put(owner, list);
    }

    /** 该主人名下的女仆；没扫到过就是空表（调用方只读，别往里写） */
    public static List<Maid> of(UUID owner) {
        return BY_OWNER.getOrDefault(owner, List.of());
    }

    /**
     * 她没了（被打死、被收走）：从名单里划掉。
     * <p>
     * 同样是"整份换掉"而不是就地 removeIf：这样不管 {@link #replace} 当初存进来的是什么实现，
     * 都不会再出现"不可变列表被改"那种崩法。
     */
    public static void forget(UUID maid) {
        for (Map.Entry<UUID, List<Maid>> entry : BY_OWNER.entrySet()) {
            List<Maid> kept = new ArrayList<>(entry.getValue());
            kept.removeIf(candidate -> candidate.id().equals(maid));
            entry.setValue(kept);
        }
    }

    /** 名单里记的名字：她所在的区块没加载时，界面还得显示她叫什么 */
    public static String nameOf(UUID maid) {
        for (List<Maid> list : BY_OWNER.values()) {
            for (Maid entry : list) {
                if (entry.id().equals(maid)) {
                    return entry.name();
                }
            }
        }
        return "";
    }

    @Nullable
    public static UUID ownerOf(UUID maid) {
        for (Map.Entry<UUID, List<Maid>> entry : BY_OWNER.entrySet()) {
            for (Maid maid2 : entry.getValue()) {
                if (maid2.id().equals(maid)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    public static void clear() {
        BY_OWNER.clear();
    }
}
