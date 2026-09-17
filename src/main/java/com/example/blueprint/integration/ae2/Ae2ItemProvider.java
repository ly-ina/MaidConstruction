package com.example.blueprint.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.security.IActionSource;
import appeng.api.networking.storage.IStorageService;
import appeng.api.stacks.AEItemKey;
import appeng.api.storage.MEStorage;
import appeng.capabilities.Capabilities;
import com.example.blueprint.build.ItemProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemHandlerHelper;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 从 ME 网络取料、也往 ME 网络里还料。
 * <p>
 * 这个类直接引用 AE2 的类型，因此**只有确认 AE2 已加载后才能触碰**
 * （见 {@link Ae2Compat}），否则会抛 NoClassDefFoundError。
 * <p>
 * 一个坐标上可能有多种方式接到网络，这里全部收集起来当候选：
 * <ul>
 *   <li>存储总线、ME 接口这类方块自身就实现了 MEStorage；</li>
 *   <li>终端、驱动器、控制器、线缆则要顺节点问到整个网络的库存；</li>
 *   <li>终端实际上是挂在线缆上的部件，承载它的线缆方块才是节点宿主，
 *       而那个方块会把能力查询代理给部件——有可能因此拿到一个
 *       "终端视角"的空视图。所以不能逮着第一个就当结果，得逐个验证。</li>
 * </ul>
 */
public class Ae2ItemProvider implements ItemProvider {

    private final Level level;
    private final BlockPos pos;

    private Ae2ItemProvider(Level level, BlockPos pos) {
        this.level = level;
        this.pos = pos;
    }

    /**
     * 尝试在给定位置建立一个 ME 取料源。
     *
     * @return 该位置接不上 ME 网络时返回 null
     */
    @Nullable
    public static ItemProvider create(Level level, BlockPos pos) {
        Ae2ItemProvider provider = new Ae2ItemProvider(level, pos);
        return provider.storages().isEmpty() ? null : provider;
    }

    @Override
    public BlockPos interactPos() {
        return pos;
    }

    /**
     * 收集这个位置上所有能访问到的网络库存视图。
     * <p>
     * 顺序上把"方块自带存储"排在"顺节点问网络"前面，因为前者更具体；
     * 但真正决定用哪一个的是调用方，它会挑第一个能取到料的。
     */
    private List<MEStorage> storages() {
        List<MEStorage> result = new ArrayList<>(4);

        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity != null) {
            // 存储总线、ME 接口：方块自身就是存储
            blockEntity.getCapability(Capabilities.STORAGE).ifPresent(result::add);

            // 有些方块只在特定面暴露节点，逐个方向问一遍
            for (Direction side : Direction.values()) {
                IInWorldGridNodeHost host =
                        blockEntity.getCapability(Capabilities.IN_WORLD_GRID_NODE_HOST, side).orElse(null);
                addFromNode(result, host, side);
            }
        }

        // 终端、驱动器、控制器、线缆的方块实体本身就是节点宿主
        IInWorldGridNodeHost host = GridHelper.getNodeHost(level, pos);
        if (host != null) {
            addFromNode(result, host, null);
            for (Direction side : Direction.values()) {
                addFromNode(result, host, side);
            }
        }
        return result;
    }

    /**
     * 顺着一个节点宿主把网络库存加进候选列表。
     * <p>
     * 整个过程包在 try 里：AE2 的部件在特定面调用 {@code getGridNode} 是有可能抛异常的，
     * 而这里只是"找一个可用的存储"，某个方向不配合就跳过，没必要让整趟取料失败。
     */
    private void addFromNode(List<MEStorage> out, @Nullable IInWorldGridNodeHost host, @Nullable Direction side) {
        if (host == null) {
            return;
        }
        IGridNode node;
        try {
            node = host.getGridNode(side);
        } catch (Throwable t) {
            return;
        }
        if (node == null) {
            return;
        }
        IGrid grid = node.getGrid();
        if (grid == null) {
            return;
        }
        IStorageService service = grid.getStorageService();
        if (service == null) {
            return;
        }
        MEStorage inventory = service.getInventory();
        if (inventory != null && !out.contains(inventory)) {
            out.add(inventory);
        }
    }

    @Override
    public boolean hasAny(Map<Item, Integer> bill) {
        for (MEStorage storage : storages()) {
            if (hasAnyIn(storage, bill)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasAnyIn(MEStorage storage, Map<Item, Integer> bill) {
        IActionSource source = IActionSource.empty();
        for (Item item : bill.keySet()) {
            AEItemKey key = AEItemKey.of(item);
            if (key == null) {
                continue;
            }
            if (storage.extract(key, 1, Actionable.SIMULATE, source) > 0) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int transferInto(IItemHandler dst, Map<Item, Integer> bill, int maxKinds) {
        // 候选可能有好几个，挑第一个真的装着所需材料的那个下手，
        // 否则会对着空视图白忙一场
        for (MEStorage storage : storages()) {
            if (hasAnyIn(storage, bill)) {
                return transferFrom(storage, dst, bill, maxKinds);
            }
        }
        return 0;
    }

    private int transferFrom(MEStorage storage, IItemHandler dst, Map<Item, Integer> bill, int maxKinds) {
        IActionSource source = IActionSource.empty();

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

            long available = storage.extract(key, lack, Actionable.SIMULATE, source);
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

            long extracted = storage.extract(key, capacity, Actionable.MODULATE, source);
            if (extracted <= 0) {
                continue;
            }

            ItemStack stack = new ItemStack(item, (int) extracted);
            ItemStack rest = ItemHandlerHelper.insertItemStacked(dst, stack, false);
            if (!rest.isEmpty()) {
                // 模拟插入通过、实际却塞不下，说明背包在两次调用之间被改动了。
                // 把多出来的塞回网络，宁可少拿也不能让物品蒸发。
                storage.insert(AEItemKey.of(rest), rest.getCount(), Actionable.MODULATE, source);
            }
            moved++;
        }
        return moved;
    }

    @Override
    public int acceptInto(IItemHandler src, Map<Item, Integer> filter) {
        for (MEStorage storage : storages()) {
            int moved = acceptInto(storage, src, filter);
            if (moved > 0) {
                return moved;
            }
        }
        return 0;
    }

    private int acceptInto(MEStorage storage, IItemHandler src, Map<Item, Integer> filter) {
        IActionSource source = IActionSource.empty();
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
            long inserted = storage.insert(key, extracted.getCount(), Actionable.MODULATE, source);
            if (inserted < extracted.getCount()) {
                // 网络收不下这么多，把多出来的原样还给背包，绝不凭空吞掉
                ItemStack rest = extracted.copyWithCount((int) (extracted.getCount() - inserted));
                src.insertItem(slot, rest, false);
            }
            moved++;
        }
        return moved;
    }
}
