package com.example.blueprint.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.security.IActionSource;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import com.example.blueprint.build.ItemProvider;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import java.util.Map;

/**
 * 在"一份 ME 存储"和"女仆背包"之间搬材料的那套动作。
 * <p>
 * 从 {@link Ae2ItemProvider} 里抽出来的，因为现在有两个来源要用同一套动作：
 * 绑定书指定的接入点（可能有多个候选视图，要挑一个真的装着料的），
 * 以及女仆身上的无线终端（只有一个视图，但它可能隔着很远甚至隔着维度）。
 * 两个来源的区别只在"怎么找到存储"，找到之后怎么搬是一模一样的。
 */
final class Ae2StorageTransfer {

    private Ae2StorageTransfer() {
    }

    private static final IActionSource SOURCE = IActionSource.empty();

    /** 这份存储里有没有清单上要的材料。尽量便宜：每种只问 1 个。 */
    static boolean hasAny(MEStorage storage, Map<Item, Integer> bill) {
        for (Item item : bill.keySet()) {
            AEItemKey key = AEItemKey.of(item);
            if (key == null) {
                continue;
            }
            if (storage.extract(key, 1, Actionable.SIMULATE, SOURCE) > 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * 按缺口把材料搬进目标背包，返回搬到的材料种类数。
     * <p>
     * 契约同 {@link ItemProvider#transferInto}：{@code bill} 是需求不是缺口，
     * 内部会先减掉背包里已有的；塞不下的绝不吞掉。
     */
    static int transferInto(MEStorage storage, IItemHandler dst, Map<Item, Integer> bill, int maxKinds) {
        // 按缺口取料，而不是见一组搬一组：结构只要 5 块石头就拿 5 块
        Map<Item, Integer> missing = ItemProvider.missingAmounts(dst, bill);
        if (missing.isEmpty()) {
            return 0;
        }

        int moved = 0;
        for (Map.Entry<Item, Integer> entry : missing.entrySet()) {
            if (moved >= maxKinds) {
                break;
            }
            Item item = entry.getKey();
            int lack = entry.getValue();
            AEItemKey key = AEItemKey.of(item);
            if (key == null) {
                continue;
            }

            long available = storage.extract(key, lack, Actionable.SIMULATE, SOURCE);
            if (available <= 0) {
                continue;
            }

            // 用 ItemStack 问堆叠上限：Item#getMaxStackSize 在 1.20 已经标记废弃
            int want = (int) Math.min(available, new ItemStack(item).getMaxStackSize());
            if (want <= 0) {
                continue;
            }

            // 先问背包收不收得下：网络的提取只返回数量，
            // 一旦提出来又塞不回去，物品就凭空消失了，只能提前确认容量。
            ItemStack probe = new ItemStack(item, want);
            ItemStack leftover = ItemHandlerHelper.insertItemStacked(dst, probe, true);
            int capacity = want - leftover.getCount();
            if (capacity <= 0) {
                continue;
            }

            long extracted = storage.extract(key, capacity, Actionable.MODULATE, SOURCE);
            if (extracted <= 0) {
                continue;
            }

            ItemStack stack = new ItemStack(item, (int) extracted);
            ItemStack rest = ItemHandlerHelper.insertItemStacked(dst, stack, false);
            if (!rest.isEmpty()) {
                // 模拟插入通过、实际却塞不下，说明背包在两次调用之间被改动了。
                // 把多出来的塞回网络，宁可少拿也不能让物品蒸发。
                storage.insert(AEItemKey.of(rest), rest.getCount(), Actionable.MODULATE, SOURCE);
            }
            moved++;
        }
        return moved;
    }

    /** 把背包里属于本次工程的材料放回存储，返回放回去的种类数。 */
    static int acceptInto(MEStorage storage, IItemHandler src, Map<Item, Integer> filter) {
        int moved = 0;
        for (int slot = 0; slot < src.getSlots(); slot++) {
            ItemStack inSlot = src.getStackInSlot(slot);
            if (inSlot.isEmpty() || !filter.containsKey(inSlot.getItem())) {
                continue;
            }

            ItemStack extracted = src.extractItem(slot, inSlot.getCount(), false);
            if (extracted.isEmpty()) {
                continue;
            }

            AEItemKey key = AEItemKey.of(extracted);
            long inserted = storage.insert(key, extracted.getCount(), Actionable.MODULATE, SOURCE);
            if (inserted < extracted.getCount()) {
                // 网络收不下这么多，把多出来的原样还给背包，绝不凭空吞掉
                ItemStack rest = extracted.copyWithCount((int) (extracted.getCount() - inserted));
                src.insertItem(slot, rest, false);
            }
            moved++;
        }
        return moved;
    }

    /**
     * 把一样东西塞进存储，返回收下的个数。
     * <p>
     * 施工时拆下来的方块走这条路回仓库（女仆背包塞不下之后的第二站）。
     * 按实际收下的个数返回，收不下就不算数——网络满了、物品被禁入（黑名单）
     * 都由 AE2 自己决定，这里只如实转达。
     */
    static int deposit(MEStorage storage, ItemStack stack) {
        if (stack.isEmpty()) {
            return 0;
        }
        AEItemKey key = AEItemKey.of(stack);
        if (key == null) {
            return 0;
        }
        long inserted = storage.insert(key, stack.getCount(), Actionable.MODULATE, SOURCE);
        return (int) Math.max(0, Math.min(inserted, stack.getCount()));
    }
}
