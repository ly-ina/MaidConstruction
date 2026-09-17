package com.example.blueprint.build;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * 旋转方块实体数据里的朝向信息。
 * <p>
 * 为什么需要它：{@link net.minecraft.world.level.block.BlockState#rotate} 只处理
 * 方块状态里的属性，碰不到方块实体 NBT。而有些方块的朝向压根不在状态里——
 * AE2 的 ME 线缆就是，它把每个部件挂在哪个面记在 NBT 的键名上
 * （{@code north}、{@code east} 之类）。
 * <p>
 * NBT 的语义只有各自的模组清楚，没法通用地猜（随便改键名很容易误伤别家的数据），
 * 所以做成注册链，各家认领自家的方块。
 */
public interface BlockEntityRotation {

    /**
     * @param state    旋转之后的方块状态
     * @param tag      原始方块实体数据，不要就地修改
     * @param rotation 旋转量
     * @return 旋转后的数据；返回 {@code null} 表示"不归我管"，交给下一个。
     */
    @Nullable
    CompoundTag rotate(BlockState state, CompoundTag tag, Rotation rotation);
}
