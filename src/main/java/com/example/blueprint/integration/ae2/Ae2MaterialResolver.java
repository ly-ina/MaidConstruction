package com.example.blueprint.integration.ae2;

import appeng.block.networking.CableBusBlock;
import com.example.blueprint.build.BlockMaterials;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/**
 * 还原 AE2 线缆方块真正需要的材料。
 * <p>
 * 为什么不能走默认的 {@code Block.asItem()}：{@code ae2:cable_bus} 只是个"容器方块"，
 * 玩家实际放置的是贴在各面上的部件（线缆本体、终端、存储总线……）。这个方块的
 * asItem() 返回的是一个游戏里拿不到的方块物品，照它去算材料，女仆会抱着一个
 * 永远找不到的物品名干等——箱子里明明躺着线缆也不会去拿。
 * <p>
 * 真正的材料记录在方块实体 NBT 里：{@code CableBusContainer.writeToNBT} 给每个部件
 * 写了一个 {@code id} 字段，值就是该部件对应物品的注册名（{@code IPartItem.getId}）。
 * 所以这里把它捞出来，一个部件算一样材料。
 */
public final class Ae2MaterialResolver implements BlockMaterials {

    /** 递归层数上限，防止在异常 NBT 上无限下钻 */
    private static final int MAX_DEPTH = 3;

    @Override
    @Nullable
    public List<Item> resolve(BlockState state, @Nullable CompoundTag blockEntityTag) {
        if (!(state.getBlock() instanceof CableBusBlock)) {
            return null;
        }

        List<Item> items = new ArrayList<>();
        if (blockEntityTag != null) {
            collectPartItems(blockEntityTag, items, 0);
        }
        // 空列表是有效答案：没有部件的裸线缆方块（比如只剩一个空壳）确实不要材料
        return items;
    }

    private static void collectPartItems(Tag tag, List<Item> out, int depth) {
        if (depth > MAX_DEPTH) {
            return;
        }

        if (tag instanceof CompoundTag compound) {
            if (compound.contains("id", Tag.TAG_STRING)) {
                Item item = itemOf(compound.getString("id"));
                if (item != null) {
                    out.add(item);
                    // 这已经是一个完整的部件记录了，别再往里钻：
                    // 部件自己的库存里也有叫 id 的字段，那些是它装的东西，不是建造材料
                    return;
                }
            }
            for (String key : compound.getAllKeys()) {
                collectPartItems(compound.get(key), out, depth + 1);
            }
        } else if (tag instanceof ListTag list) {
            for (Tag element : list) {
                collectPartItems(element, out, depth + 1);
            }
        }
    }

    @Nullable
    private static Item itemOf(String id) {
        ResourceLocation key = ResourceLocation.tryParse(id);
        if (key == null) {
            return null;
        }
        Item item = ForgeRegistries.ITEMS.getValue(key);
        return item == null || item == Items.AIR ? null : item;
    }
}
