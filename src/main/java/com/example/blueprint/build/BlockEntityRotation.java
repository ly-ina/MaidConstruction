package com.example.blueprint.build;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

/**
 * 变换方块实体数据里的朝向信息（换朝向时，NBT 里的那部分跟着走）。
 * <p>
 * 为什么需要它：{@link net.minecraft.world.level.block.BlockState#rotate} 只处理
 * 方块状态里的属性，碰不到方块实体 NBT。而有些方块的朝向压根不在状态里——
 * AE2 的 ME 线缆就是，它把每个部件挂在哪个面记在 NBT 的键名上
 * （{@code north}、{@code east} 之类）。
 * <p>
 * NBT 的语义只有各自的模组清楚，没法通用地猜（随便改键名很容易误伤别家的数据），
 * 所以做成注册链，各家认领自家的方块。
 * <p>
 * 镜像与旋转一起交给实现：两者对朝向的作用是同一件事的两半
 * （先镜像、后旋转，见 {@link Schematic#mirror}），拆成两个方法的话，
 * 各家的实现里都要再写一遍"顺序"，容易两边不一致。
 */
public interface BlockEntityRotation {

    /**
     * @param state    变换之后的方块状态
     * @param tag      原始方块实体数据，不要就地修改
     * @param rotation 旋转量
     * @param mirror   翻面量（先翻面、后旋转）
     * @return 变换后的数据；返回 {@code null} 表示"不归我管"，交给下一个。
     */
    @Nullable
    CompoundTag transform(BlockState state, CompoundTag tag, Rotation rotation, Mirror mirror);
}
