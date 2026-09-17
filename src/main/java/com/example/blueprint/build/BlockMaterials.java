package com.example.blueprint.build;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;
import java.util.List;

/**
 * 回答"建造这个方块需要哪些物品"。
 * <p>
 * 绝大多数方块用默认实现就够了——{@code Block.asItem()} 拿到什么就是什么。
 * 但有些方块不是：典型的是 AE2 的 ME 线缆。
 * <p>
 * 那个方块（{@code ae2:cable_bus}）只是一个"容器"，真正放上去的是贴在各面上的部件
 * （线缆本体、终端、存储总线……），而它的 {@code asItem()} 返回的是一个游戏里
 * 根本拿不到的方块物品。照这个去算材料，女仆就会拿着一个永远找不到的物品名
 * 干瞪眼，明明箱子里躺着线缆也不会去拿。
 * <p>
 * 这类方块的正确材料藏在方块实体 NBT 里，所以解析器要能同时看到状态和 NBT。
 */
public interface BlockMaterials {

    /**
     * @param state          方块状态
     * @param blockEntityTag 该方块的实体 NBT（已剔除坐标字段），可能为 null
     * @return 需要的物品列表；返回 {@code null} 表示"不认识这个方块"，
     * 由下一个解析器接手，最终回落到 {@code Block.asItem()}。
     * 返回空列表表示"这个方块不需要材料"。
     */
    @Nullable
    List<Item> resolve(BlockState state, @Nullable CompoundTag blockEntityTag);
}
