package com.example.blueprint.integration.ae2;

import appeng.api.networking.IGrid;
import appeng.api.storage.MEStorage;
import com.example.blueprint.build.ItemProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.util.Map;

/**
 * 女仆身上那台**无线女仆终端**所连的网络，作为一个取料来源。
 * <p>
 * 和 {@link Ae2ItemProvider} 的区别只在"怎么找到存储"：那个是从绑定坐标顺节点问过去，
 * 这个是从终端里存的绑定信息解析出网格。找到之后的搬运完全共用
 * {@link Ae2StorageTransfer}。
 * <p>
 * 它覆写 {@code requiresTravel()} 返回 false：女仆已经站在终端旁边了，
 * 没有可走的地方。不这么做，控制器会照着绑定坐标一路跑过去——那可能是地图另一头。
 */
public class Ae2WirelessProvider implements ItemProvider {

    /** 就地取料，这个坐标只是为了满足接口；调用方会跳过一切导航和开箱动画 */
    private static final BlockPos NO_TRAVEL_POS = BlockPos.ZERO;

    private final MEStorage storage;

    private Ae2WirelessProvider(MEStorage storage) {
        this.storage = storage;
    }

    /**
     * 从女仆身上带着的物品里挑一台能用的无线终端，建成取料源。
     *
     * @param userPos 女仆的位置，用来判定有没有处在无线接入点的覆盖范围内
     * @return 没有终端、没绑定、接不上网络、或者不在覆盖范围内时返回 null
     */
    @Nullable
    public static ItemProvider create(Level level, Iterable<ItemStack> candidates, @Nullable net.minecraft.world.phys.Vec3 userPos) {
        for (ItemStack stack : candidates) {
            if (!(stack.getItem() instanceof WirelessMaidTerminalItem)) {
                continue;
            }
            IGrid grid = WirelessMaidLink.resolveGrid(level, stack);
            if (grid == null) {
                continue;
            }
            // 插了卡就不看距离（此时 resolveGrid 也才允许跨维度）；
            // 没插卡必须站在终端链接的那台无线访问点的射程里，
            // 规则跟玩家用官方无线终端时一样
            if (!WirelessMaidLink.hasBindingCard(stack)
                    && !WirelessMaidLink.withinRange(WirelessMaidLink.linkedAccessPoint(level, stack), userPos)) {
                continue;
            }
            MEStorage storage = WirelessMaidLink.getStorage(grid);
            if (storage != null) {
                return new Ae2WirelessProvider(storage);
            }
        }
        return null;
    }

    @Override
    public BlockPos interactPos() {
        return NO_TRAVEL_POS;
    }

    @Override
    public boolean requiresTravel() {
        return false;
    }

    @Override
    public boolean hasAny(Map<Item, Integer> bill) {
        return Ae2StorageTransfer.hasAny(storage, bill);
    }

    @Override
    public int transferInto(IItemHandler dst, Map<Item, Integer> bill, int maxKinds) {
        return Ae2StorageTransfer.transferInto(storage, dst, bill, maxKinds);
    }

    @Override
    public int acceptInto(IItemHandler src, Map<Item, Integer> filter) {
        return Ae2StorageTransfer.acceptInto(storage, src, filter);
    }
}
