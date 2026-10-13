package com.example.blueprint.server;

import com.example.blueprint.block.CommandPostBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;

import javax.annotation.Nullable;

/**
 * 一个玩家同时**只能绑一台**指挥台：这里记着"他现在绑的是哪一台"。
 * <p>
 * <b>为什么还得单独记一份</b>：许可写在各台的方块实体上（{@code owner} 字段），可换绑第二台时，
 * 第一台多半在几百格外的**未加载区块**里——连它的方块实体都取不到，"把第一台解绑"就无从下手。
 * 于是"现在绑的是哪一台"必须**按玩家**另记一份。
 * <p>
 * <b>为什么放在玩家的持久数据里</b>：它本来就是"这个人的一件事"，跟着存档走、跨维度也在、重登还在
 * （与 {@code ManualGiveaway} 那条"发过说明书没有"同一个口袋）。
 * <p>
 * <b>旧 owner 是一句过期的话</b>：换绑时旧那台若没加载，它方块实体上的 {@code owner} 清不掉。
 * 所以"这台归他管吗"一律以这份记录为准（见 {@link #isCurrent} / {@link #canUse}），
 * 方块实体上那个字段只用来显示"谁绑的"与拦住别人；等他哪天走回那台、再右键一次，两处就对上了。
 * <p>
 * <b>老存档（1.8.3 及以前）没有这份记录</b>：那时可以绑多台。第一次用到某个绑定动作时**就地认下**
 * （把当前这一台记成他的），于是不会把人锁在门外——见 {@link #isCurrent}。
 */
public final class CommandPostBindings {

    /** 他绑的那一台在哪个维度（不记维度的话，换个世界坐标可能刚好又对上了） */
    private static final String KEY_DIMENSION = "maidconstruction:bound_post_dimension";
    /** 那一格的坐标，{@link BlockPos#asLong()} */
    private static final String KEY_POS = "maidconstruction:bound_post_pos";

    private CommandPostBindings() {
    }

    /** 他现在绑的是哪一台；没绑过返回 null */
    @Nullable
    public static GlobalPos current(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        if (!data.contains(KEY_DIMENSION) || !data.contains(KEY_POS)) {
            return null;
        }
        ResourceLocation dimension = ResourceLocation.tryParse(data.getString(KEY_DIMENSION));
        if (dimension == null) {
            return null;
        }
        return GlobalPos.of(ResourceKey.create(Registries.DIMENSION, dimension),
                BlockPos.of(data.getLong(KEY_POS)));
    }

    /**
     * 他绑的是这一台吗。
     * <p>
     * <b>没有记录时返回 true 并顺手认下</b>：1.8.3 及以前绑的那几台没有记录，那时他可以绑多台；
     * 第一次用到就把当前这一台记成他的。认下而不是拒绝，是因为拒绝会把他锁在门外——他明明绑着，
     * 却谁也动不了，而"哪台才算"本来就只能由这一次使用来定义。
     */
    public static boolean isCurrent(ServerPlayer player, GlobalPos post) {
        GlobalPos current = current(player);
        if (current == null) {
            remember(player, post);
            return true;
        }
        return current.equals(post);
    }

    /** 他能动这一台吗：主人是他，而且还得记着他**现在**绑的就是这一台 */
    public static boolean canUse(ServerPlayer player, ServerLevel level, CommandPostBlockEntity post) {
        return post.isBoundTo(player.getUUID())
                && isCurrent(player, GlobalPos.of(level.dimension(), post.getBlockPos()));
    }

    /**
     * 绑定这一台，并把**原来那台解绑**——这就是"只能绑一台"。
     *
     * @return 被换下来的那一台；没换绑（原来是空、或就是这一台）时 null
     */
    @Nullable
    public static GlobalPos bind(ServerPlayer player, GlobalPos post) {
        GlobalPos previous = current(player);
        remember(player, post);
        if (previous == null || previous.equals(post)) {
            return null;
        }
        unbindPost(player, previous);
        return previous;
    }

    /** 玩家自己解绑（潜行右键）：清掉记录 */
    public static void unbind(ServerPlayer player) {
        forget(player);
    }

    private static void remember(ServerPlayer player, GlobalPos post) {
        CompoundTag data = player.getPersistentData();
        data.putString(KEY_DIMENSION, post.dimension().location().toString());
        data.putLong(KEY_POS, post.pos().asLong());
    }

    private static void forget(ServerPlayer player) {
        CompoundTag data = player.getPersistentData();
        data.remove(KEY_DIMENSION);
        data.remove(KEY_POS);
    }

    /**
     * 旧那台方块实体上的 {@code owner} 是**能清就清**：它在这个维度、区块也还加载着，就顺手清掉；
     * 取不到（多半就是没加载）就算了——那份字段从此只是一句过期的话，判定看的是 {@link #current}。
     */
    private static void unbindPost(ServerPlayer player, GlobalPos post) {
        ServerLevel level = player.server.getLevel(post.dimension());
        if (level == null) {
            return;
        }
        BlockEntity be = level.getBlockEntity(post.pos());
        if (be instanceof CommandPostBlockEntity commandPost && commandPost.isBoundTo(player.getUUID())) {
            commandPost.unbind();
        }
    }
}
