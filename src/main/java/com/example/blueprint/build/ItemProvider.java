package com.example.blueprint.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import java.util.HashMap;
import java.util.Map;

/**
 * 女仆可以搬走、也可以放回材料的来源。
 * <p>
 * 存在的意义是把"材料从哪来"这件事和主流程解耦：
 * 控制器的取料逻辑只认这个接口，不用关心对面是一个普通箱子、
 * 一个 ME 网络，还是将来某种还没出现的存储方式。
 * <p>
 * 之所以不直接用 {@code IItemHandler}：AE2 这类网络存储根本没有
 * 槽位概念，只有"某种物品能取多少"这一层抽象，
 * 硬套槽位接口会写出一堆假实现。
 */
public interface ItemProvider {

    /**
     * 女仆需要走到哪个位置才能取料。
     * <p>
     * 对于 ME 网络，返回的是接入点（存储总线、接口等）所在的位置，
     * 而不是网络里某台机器的位置。
     */
    BlockPos interactPos();

    /**
     * 女仆是否必须走到 {@link #interactPos()} 才能取料。
     * <p>
     * 容器和绑定书指定的仓库都要走过去，所以默认是 {@code true}。
     * 但"女仆自己身上带着的无线终端"这种来源没有可走的地方——她已经站在终端旁边了，
     * 再照着绑定坐标一路跑过去只会跑到别的维度或者地图另一头。
     * 这类来源覆写成 {@code false}，控制器的取料和还料都会就地完成。
     */
    default boolean requiresTravel() {
        return true;
    }

    /**
     * 这个来源目前是否还有建造所需的材料。
     * <p>
     * 用于"值不值得跑这一趟"的判断，因此应当尽量便宜。
     */
    boolean hasAny(Map<Item, Integer> bill);

    /**
     * 按需把材料搬进目标背包。
     *
     * @param dst      目的地，通常是女仆自己的背包
     * @param bill     建造总共需要哪些材料、各要多少
     * @param maxKinds 这一次最多搬几种材料，避免为了每种方块来回跑
     * @return 实际搬进去的材料种类数；0 表示什么都没搬到
     */
    int transferInto(IItemHandler dst, Map<Item, Integer> bill, int maxKinds);

    /**
     * 把 src 里的材料放回来。
     * <p>
     * 只处理 filter 里列出的物品，女仆自己的杂物不会被牵连进去。
     * 默认实现什么也不做，"只能取不能存"的来源不必覆写。
     *
     * @return 实际放回去的材料种类数
     */
    default int acceptInto(IItemHandler src, Map<Item, Integer> filter) {
        return 0;
    }

    /**
     * 把一样"杂物"塞进这个来源。
     * <p>
     * 和 {@link #acceptInto} 的区别是**不认清单**：施工时从地上拆下来的东西五花八门
     * （草方块、树叶、机器的零件），事先列不出清单来。默认什么也不做，
     * "只能取不能存"的来源不必覆写。
     *
     * @return 收下了几个；0 表示一个都没收下。剩下的由调用方另行处置，
     *         **绝不能当作已经收下**——那样物品就蒸发了
     */
    default int deposit(ItemStack stack) {
        return 0;
    }

    /**
     * 算出清单上每种材料还缺多少。
     * <p>
     * 取料要按需而不是按组：结构只要 5 块石头，就不该从箱子里掏 64 块出来。
     * 这里把"需求减去背包已有"一次算好，留给实现循环使用，
     * 免得在槽位循环里反复翻背包。
     */
    static Map<Item, Integer> missingAmounts(IItemHandler dst, Map<Item, Integer> bill) {
        Map<Item, Integer> missing = new HashMap<>();
        for (Map.Entry<Item, Integer> entry : bill.entrySet()) {
            int have = 0;
            for (int i = 0; i < dst.getSlots(); i++) {
                ItemStack stack = dst.getStackInSlot(i);
                if (stack.is(entry.getKey())) {
                    have += stack.getCount();
                }
            }
            int lack = entry.getValue() - have;
            if (lack > 0) {
                missing.put(entry.getKey(), lack);
            }
        }
        return missing;
    }
}
