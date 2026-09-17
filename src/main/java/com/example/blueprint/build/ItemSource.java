package com.example.blueprint.build;

import net.minecraft.world.item.Item;

/**
 * 施工时的材料来源抽象。
 * <p>
 * 蓝图本身不能一键放置，施工全部由女仆完成，
 * 因此目前只有 {@code MaidItemSource} 一个实现；
 * 保留接口是为了让建造逻辑不必了解女仆的物品栏细节。
 */
public interface ItemSource {

    /** 该材料当前可取数量 */
    int available(Item item);

    /** 尝试消耗 count 个，成功返回 true */
    boolean consume(Item item, int count);
}
