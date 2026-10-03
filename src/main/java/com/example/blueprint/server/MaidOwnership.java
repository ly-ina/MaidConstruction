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

    /** 重新确定某位主人名下的女仆（进游戏时调一次，先清掉旧的，免得留下已经不在的） */
    public static void replace(UUID owner, List<Maid> maids) {
        BY_OWNER.put(owner, List.copyOf(maids));
    }

    public static void remember(Maid maid, UUID owner) {
        BY_OWNER.computeIfAbsent(owner, key -> new ArrayList<>()).add(maid);
    }

    /** 该主人名下的女仆；没扫到过就是空表 */
    public static List<Maid> of(UUID owner) {
        return BY_OWNER.getOrDefault(owner, List.of());
    }

    public static void forget(UUID maid) {
        BY_OWNER.values().forEach(list -> list.removeIf(entry -> entry.id().equals(maid)));
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
