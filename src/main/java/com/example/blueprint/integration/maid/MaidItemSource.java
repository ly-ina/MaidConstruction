package com.example.blueprint.integration.maid;

import com.example.blueprint.build.ItemSource;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;

/**
 * 女仆的材料来源。
 * <p>
 * 优先用女仆背包（通过 Forge 的物品栏能力获取），
 * 万一女仆模组没有暴露该能力，就退回到副手槽——一次搬一组，慢但不会失效。
 */
public class MaidItemSource implements ItemSource {

    private final EntityMaid maid;

    public MaidItemSource(EntityMaid maid) {
        this.maid = maid;
    }

    @Nullable
    public IItemHandler getBackpack() {
        return maid.getCapability(ForgeCapabilities.ITEM_HANDLER).orElse(null);
    }

    @Override
    public int available(Item item) {
        IItemHandler backpack = getBackpack();
        if (backpack != null) {
            int total = 0;
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (stack.is(item)) {
                    total += stack.getCount();
                }
            }
            return total;
        }
        ItemStack offHand = maid.getOffhandItem();
        return offHand.is(item) ? offHand.getCount() : 0;
    }

    @Override
    public boolean consume(Item item, int count) {
        IItemHandler backpack = getBackpack();
        if (backpack != null) {
            int remaining = count;
            for (int i = 0; i < backpack.getSlots() && remaining > 0; i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (stack.is(item)) {
                    remaining -= backpack.extractItem(i, remaining, false).getCount();
                }
            }
            return remaining <= 0;
        }

        ItemStack offHand = maid.getOffhandItem();
        if (offHand.is(item) && offHand.getCount() >= count) {
            offHand.shrink(count);
            maid.setItemInHand(InteractionHand.OFF_HAND, offHand);
            return true;
        }
        return false;
    }
}
