package com.example.blueprint.integration.ae2;

import appeng.block.networking.CableBusBlock;
import com.example.blueprint.build.BlockEntityRotation;
import com.example.blueprint.schematic.Schematic;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 变换 AE2 线缆上各部件的朝向（旋转与镜像都走这里）。
 * <p>
 * ME 线缆的方块状态里只有亮度和含水两个属性，部件挂在哪个面是记在方块实体 NBT 的
 * 键名上的：{@code north}、{@code east}……每个部件占一个键。结构换朝向时方块状态会跟着走，
 * 但 NBT 是原样搬运的，键名还停在原朝向——于是终端转完还在原来那一面。
 * <p>
 * 这里把键名连同它的内容一起挪到变换后的方向上去。顺序是**先翻面、后旋转**，
 * 与 {@link Schematic#mirror} 那边的坐标映射一致；顺序反了部件会落在错误的面。
 */
public final class Ae2BlockEntityRotation implements BlockEntityRotation {

    @Override
    @Nullable
    public CompoundTag transform(BlockState state, CompoundTag tag, Rotation rotation, Mirror mirror) {
        if (!(state.getBlock() instanceof CableBusBlock)) {
            return null;
        }

        // 先把"哪个键搬到哪里"整个算出来，再动手改。
        // 不能边搬边查：搬过去的键会被后面的迭代当成源再处理一遍，
        // 结果就是多转一格（朝南的转成朝西，又被转成朝北）。
        List<String> sources = new ArrayList<>();
        List<String> targets = new ArrayList<>();
        for (Direction side : Direction.values()) {
            Direction moved = Schematic.rotateDirection(Schematic.mirrorDirection(side, mirror), rotation);
            if (moved == side || !tag.contains(side.getName())) {
                continue;
            }
            sources.add(side.getName());
            targets.add(moved.getName());
        }

        if (sources.isEmpty()) {
            return tag;
        }

        CompoundTag result = tag.copy();
        // 摘除和放回也要分成两批：合在一步里的话，后一次 remove 会把
        // 前一次刚放好的内容又删掉（180° 那种方向互换的情况尤其明显）
        for (String source : sources) {
            result.remove(source);
        }
        for (int i = 0; i < sources.size(); i++) {
            Tag value = tag.get(sources.get(i));
            if (value != null) {
                result.put(targets.get(i), value.copy());
            }
        }
        return result;
    }
}
