package com.example.blueprint.build;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 普通方块容器：箱子、桶、潜影盒，以及任何对外暴露标准物品栏能力的机器。
 * <p>
 * 每次操作都重新取一遍能力，而不是构造时抓一次存着——
 * 容器可能在女仆走过来的路上被拆掉或换掉，缓存引用会导致幽灵取料。
 */
public class BlockContainerProvider implements ItemProvider {

    private final Level level;
    private final BlockPos pos;

    public BlockContainerProvider(Level level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    @Override
    public BlockPos interactPos() {
        return pos;
    }

    @Nullable
    private IItemHandler handler() {
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }
        return blockEntity.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
    }

    @Override
    public boolean hasAny(Map<Item, Integer> bill) {
        IItemHandler handler = handler();
        if (handler == null) {
            return false;
        }
        for (int i = 0; i < handler.getSlots(); i++) {
            ItemStack stack = handler.getStackInSlot(i);
            if (!stack.isEmpty() && bill.containsKey(stack.getItem())) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int transferInto(IItemHandler dst, Map<Item, Integer> bill, int maxKinds) {
        IItemHandler source = handler();
        if (source == null) {
            return 0;
        }

        // 先算出每种材料还缺多少。取料是按缺口取，不是见一组搬一组：
        // 结构只要 5 块石头，就从箱子里拿 5 块
        Map<Item, Integer> missing = ItemProvider.missingAmounts(dst, bill);
        if (missing.isEmpty()) {
            return 0;
        }

        int moved = 0;
        for (int slot = 0; slot < source.getSlots() && moved < maxKinds; slot++) {
            ItemStack inSlot = source.getStackInSlot(slot);
            if (inSlot.isEmpty()) {
                continue;
            }
            Integer lack = missing.get(inSlot.getItem());
            if (lack == null || lack <= 0) {
                continue;
            }

            ItemStack extracted = source.extractItem(slot, Math.min(lack, inSlot.getCount()), false);
            if (extracted.isEmpty()) {
                continue;
            }

            ItemStack remainder = ItemHandlerHelper.insertItemStacked(dst, extracted, false);
            if (!remainder.isEmpty()) {
                // 背包装不下就原样还回去，绝不吞玩家的东西
                source.insertItem(slot, remainder, false);
                return moved;
            }
            missing.merge(inSlot.getItem(), -extracted.getCount(), Integer::sum);
            moved++;
        }
        return moved;
    }

    @Override
    public int acceptInto(IItemHandler src, Map<Item, Integer> filter) {
        IItemHandler dest = handler();
        if (dest == null) {
            return 0;
        }

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

            ItemStack remainder = ItemHandlerHelper.insertItemStacked(dest, extracted, false);
            if (!remainder.isEmpty()) {
                // 容器满了，把塞不进去的还给女仆，让她带在身上
                src.insertItem(slot, remainder, false);
                if (remainder.getCount() == extracted.getCount()) {
                    // 一个都没放进去，多半是满了，没必要再试其它槽位
                    return moved;
                }
            }
            moved++;
        }
        return moved;
    }
}
