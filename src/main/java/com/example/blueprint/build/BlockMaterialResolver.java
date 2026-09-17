package com.example.blueprint.build;

import com.example.blueprint.schematic.Schematic;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;

/**
 * 材料解析器链。
 * <p>
 * 各模组的解析器按注册顺序依次询问，第一个"认识这个方块"的说了算；
 * 都说不认识时回落到 {@code Block.asItem()}——也就是绝大多数方块的常规行为。
 * <p>
 * 用注册链而不是在核心包直接 import 各家模组的类，是为了守住软依赖的边界：
 * 核心的建造逻辑不该因为某个模组没装就加载不了。
 */
public final class BlockMaterialResolver {

    private static final List<BlockMaterials> RESOLVERS = new ArrayList<>();

    private BlockMaterialResolver() {
    }

    public static void register(BlockMaterials resolver) {
        RESOLVERS.add(resolver);
    }

    /**
     * 建造这个方块需要哪些物品。
     * <p>
     * 返回的是列表而不是单个物品：一个方块位置可能需要好几样东西，
     * AE2 的线缆就是——线缆本体一种，贴上去的终端、存储总线各算一样。
     */
    public static List<Item> materialsOf(Schematic.BlockEntry entry) {
        for (BlockMaterials resolver : RESOLVERS) {
            List<Item> resolved = resolver.resolve(entry.state(), entry.blockEntity());
            if (resolved != null) {
                return resolved;
            }
        }

        Item item = entry.state().getBlock().asItem();
        return item == Items.AIR ? List.of() : List.of(item);
    }
}
