package com.example.blueprint.server;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;

/**
 * 玩家持久数据里"跨死亡还在"的那一层。
 * <p>
 * <b>{@code player.getPersistentData()} 的根节点不跨死亡。</b> Forge 在
 * {@code ServerPlayer#restoreFrom}（玩家重生、以及"死亡后重进"走的同一条路）里只复制
 * **{@code PlayerPersisted} 这个子标签**：
 * <pre>
 * CompoundTag old = oldPlayer.getPersistentData();
 * if (old.contains(PERSISTED_NBT_TAG))
 *     newPlayer.getPersistentData().put(PERSISTED_NBT_TAG, old.get(PERSISTED_NBT_TAG));
 * </pre>
 * 根节点上别的键照样进存档、重登也还在，可**只要死一次就没了**——于是"发过说明书没有"
 * 这类一次性标记会被当成"还没发过"，下次登录再发一本（2.1.1 收到过这条报障：
 * 死亡重生后退出再进又收到一本，反复死亡反复发）。
 * <p>
 * 所以凡是"一辈子只该做一次"的玩家标记都写进 {@link #persisted}，别直接写根节点。
 * 临时状态（那就算了）不要放这里：它会被一直带着走。
 */
public final class PlayerData {

    private PlayerData() {
    }

    /**
     * 拿"跨死亡"那一层（没有就现挂一个）。
     * <p>
     * 必须 {@code put} 回去：{@code getCompound} 在键不存在时返回的是一个**没有挂上去**的新标签，
     * 直接往里写等于写进了空气。
     */
    public static CompoundTag persisted(ServerPlayer player) {
        CompoundTag root = player.getPersistentData();
        CompoundTag data = root.getCompound(Player.PERSISTED_NBT_TAG);
        root.put(Player.PERSISTED_NBT_TAG, data);
        return data;
    }

    /**
     * 把老版本写在**根节点**上的键搬进 {@link #persisted}，免得升级之后每个人都再"第一次"一次。
     * <p>
     * 老版本（2.1.1 及以前）直接写在根节点上：没死过的玩家那里还有，搬过来就保住了；
     * 已经死过的人本来就丢了，这次搬不到属于无从补救——那正是要修的那个 bug。
     */
    public static void adoptLegacyRootKeys(ServerPlayer player, String... keys) {
        CompoundTag root = player.getPersistentData();
        boolean present = false;
        for (String key : keys) {
            if (root.contains(key)) {
                present = true;
                break;
            }
        }
        if (!present) {
            return;
        }
        CompoundTag data = persisted(player);
        for (String key : keys) {
            Tag value = root.get(key);
            if (value != null) {
                data.put(key, value.copy());
            }
        }
        for (String key : keys) {
            root.remove(key);
        }
    }
}
