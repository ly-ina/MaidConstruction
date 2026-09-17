package com.example.blueprint.build;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * 方块实体旋转器链。
 * <p>
 * 按注册顺序依次询问，第一个"认领这个方块"的说了算；都不认就原样返回。
 * 和 {@link BlockMaterialResolver} 同样的思路：把模组相关的知识关在各自的包里，
 * 核心的旋转逻辑不必知道 AE2 的存在。
 */
public final class BlockEntityRotationResolver {

    private static final List<BlockEntityRotation> ROTATORS = new ArrayList<>();

    private BlockEntityRotationResolver() {
    }

    public static void register(BlockEntityRotation rotator) {
        ROTATORS.add(rotator);
    }

    /** 没有旋转（NONE）或没人认领时，原样返回，不产生复制 */
    public static CompoundTag rotate(BlockState state, CompoundTag tag, Rotation rotation) {
        if (rotation == Rotation.NONE) {
            return tag;
        }
        for (BlockEntityRotation rotator : ROTATORS) {
            CompoundTag rotated = rotator.rotate(state, tag, rotation);
            if (rotated != null) {
                return rotated;
            }
        }
        return tag;
    }
}
