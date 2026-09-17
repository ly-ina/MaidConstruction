package com.example.blueprint.integration.maid;

import com.example.blueprint.build.ItemSource;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;

/**
 * 女仆的材料来源，同时也是取料时的搬运目的地。
 * <p>
 * 只认女仆的背包，不碰主手、副手和盔甲——施工期间手上要拿蓝图，
 * 材料挤进去会把蓝图顶掉，外观上也会变成"攥着一把石头走路"。
 */
public class MaidItemSource implements ItemSource {

    private final EntityMaid maid;

    public MaidItemSource(EntityMaid maid) {
        this.maid = maid;
    }

    /**
     * 女仆的背包。
     * <p>
     * 这里刻意用 {@code getMaidInv()} 而不是查 ITEM_HANDLER 能力：
     * 那个能力返回的是一个组合视图（MaidInvWrapper 继承自 CombinedInvWrapper），
     * 把主手、副手、盔甲统统算在里面，往里塞材料就会出现
     * "建筑材料跑到女仆装备栏里"的情况。
     */
    @Nullable
    public IItemHandler getBackpack() {
        return maid.getMaidInv();
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
