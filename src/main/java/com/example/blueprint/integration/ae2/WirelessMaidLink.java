package com.example.blueprint.integration.ae2;

import appeng.api.config.Actionable;
import appeng.api.config.PowerMultiplier;
import appeng.api.implementations.blockentities.IWirelessAccessPoint;
import appeng.api.networking.GridHelper;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.IInWorldGridNodeHost;
import appeng.api.networking.energy.IEnergyService;
import appeng.api.networking.storage.IStorageService;
import appeng.api.storage.MEStorage;
import appeng.api.upgrades.IUpgradeableItem;
import appeng.api.upgrades.IUpgradeInventory;
import appeng.capabilities.Capabilities;
import appeng.items.tools.powered.WirelessTerminalItem;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/**
 * 无线女仆终端的"接线员"：解析链接、判断射程、从网络取电。
 * <p>
 * 这个类直接引用 AE2 的类型，**只有确认 AE2 已加载后才能触碰**（见 {@link Ae2Compat}）。
 * 它刻意不引用 TLM 的类型：玩家用终端和女仆用终端走的是同一条路。
 * <p>
 * <b>链接方式跟 AE2 官方的无线终端完全一致</b>：把终端放进 **ME 无线访问点**的槽位里链接，
 * 链接信息就是访问点的坐标 + 维度，由 AE2 自己的 {@code LINKABLE_HANDLER} 写进物品 NBT。
 * 本项目不再自己存一份坐标——那样只会和 AE2 的行为分叉。
 */
public final class WirelessMaidLink {

    private WirelessMaidLink() {
    }

    // ------------------------------------------------------------------
    // 链接
    // ------------------------------------------------------------------

    /**
     * 终端链接到的无线访问点位置。
     * <p>
     * 直接问 AE2 要（{@code getLinkedPosition} 读的是它自己写的 NBT 键），
     * 这样"访问点界面里链接"和"这里解析"永远是同一份数据。
     */
    @Nullable
    public static GlobalPos linkedPosition(ItemStack stack) {
        return stack.getItem() instanceof WirelessTerminalItem terminal
                ? terminal.getLinkedPosition(stack)
                : null;
    }

    /** 解除链接。等价于访问点界面里的"断开"。 */
    public static void unlink(ItemStack stack) {
        WirelessTerminalItem.LINKABLE_HANDLER.unlink(stack);
    }

    /**
     * 终端里插着女仆绑定卡吗。
     * <p>
     * 判据是 AE2 的升级槽里有没有这张卡，而不是自己另存一个标志位——
     * 这样插拔、拆下来换到别的终端上，行为都自然跟着走。
     */
    public static boolean hasBindingCard(ItemStack terminal) {
        if (!(terminal.getItem() instanceof IUpgradeableItem upgradeable)) {
            return false;
        }
        IUpgradeInventory upgrades = upgradeable.getUpgrades(terminal);
        return upgrades.isInstalled(Ae2TerminalRegistry.MAID_BINDING_CARD.get());
    }

    // ------------------------------------------------------------------
    // 解析网格
    // ------------------------------------------------------------------

    /**
     * 解析终端链接的那张网络。
     * <p>
     * 正常路径是"链接的那个无线访问点正在工作 → 取它的网格"。跨维度需要女仆绑定卡：
     * 没插卡时直接返回 null。插了卡才会去对应的维度找。
     * <p>
     * <b>注意</b>：不管插没插卡，目标区块没加载就拿不到网格——ME 网络只存在于
     * 已加载的区块里，AE2 自己也做不到隔空访问。所以"无视距离"的前提是
     * 那边有区块加载器之类的东西撑着。
     */
    @Nullable
    public static IGrid resolveGrid(Level level, ItemStack terminal) {
        GlobalPos linked = linkedPosition(terminal);
        if (linked == null) {
            return null;
        }
        boolean crossDimension = !linked.dimension().equals(level.dimension());
        if (crossDimension && !hasBindingCard(terminal)) {
            return null;
        }

        Level target = level;
        if (crossDimension) {
            if (level.getServer() == null) {
                return null;
            }
            target = level.getServer().getLevel(linked.dimension());
        }
        if (target == null || !target.isLoaded(linked.pos())) {
            return null;
        }

        // 链接的本该是无线访问点。它没在跑（没电、没频道）就等于没网络
        if (target.getBlockEntity(linked.pos()) instanceof IWirelessAccessPoint accessPoint) {
            return accessPoint.isActive() ? accessPoint.getGrid() : null;
        }
        // 兜底：万一链接到了别的东西（比如别的模组也用了这套链接机制），
        // 只要它是个网格节点宿主就照常解析
        return gridAt(target, linked.pos());
    }

    /**
     * 终端链接的那台无线访问点，用来算射程。
     *
     * @return 链接位置不是访问点、或者区块没加载时返回 null
     */
    @Nullable
    public static IWirelessAccessPoint linkedAccessPoint(Level level, ItemStack terminal) {
        GlobalPos linked = linkedPosition(terminal);
        if (linked == null || !linked.dimension().equals(level.dimension())) {
            // 隔着维度没有"距离"可言，交给绑定卡去处理
            return null;
        }
        if (!level.isLoaded(linked.pos())) {
            return null;
        }
        return level.getBlockEntity(linked.pos()) instanceof IWirelessAccessPoint accessPoint
                ? accessPoint
                : null;
    }

    /**
     * 使用者是否处在这台无线访问点的覆盖范围内。
     * <p>
     * 规则跟 AE2 官方无线终端一致：访问点要**正在工作**，且使用者离它不超过它的射程
     * （往访问点里插无线增幅卡，射程会变大）。
     *
     * @param userPos 使用者的位置。传 null 视为不在范围内
     */
    public static boolean withinRange(@Nullable IWirelessAccessPoint accessPoint, @Nullable Vec3 userPos) {
        if (accessPoint == null || userPos == null || !accessPoint.isActive()) {
            return false;
        }
        double range = accessPoint.getRange();
        return accessPoint.getLocation().getPos().distToCenterSqr(userPos.x, userPos.y, userPos.z)
                <= range * range;
    }

    /**
     * 只在这个维度、这个坐标上找网格。
     * <p>
     * 探测顺序跟 {@link Ae2ItemProvider} 收集库存时一样：先问方块实体自己，
     * 再按方向逐个问能力。有些设备只在特定面暴露节点，只试 {@code null} 会漏掉。
     */
    @Nullable
    public static IGrid gridAt(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) {
            return null;
        }
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (blockEntity == null) {
            return null;
        }

        IGrid fromSelf = gridOf(GridHelper.getNodeHost(level, pos), null);
        if (fromSelf != null) {
            return fromSelf;
        }
        for (Direction side : Direction.values()) {
            IInWorldGridNodeHost sideHost =
                    blockEntity.getCapability(Capabilities.IN_WORLD_GRID_NODE_HOST, side).orElse(null);
            IGrid fromSide = gridOf(sideHost, side);
            if (fromSide != null) {
                return fromSide;
            }
        }
        return null;
    }

    /**
     * 问一个节点宿主要网格。
     * <p>
     * 整个过程包在 try 里：AE2 的部件在特定面调用 {@code getGridNode} 有可能抛异常，
     * 而这里只是"探一探"，某个方向不配合就当没有。
     */
    @Nullable
    private static IGrid gridOf(@Nullable IInWorldGridNodeHost host, @Nullable Direction side) {
        if (host == null) {
            return null;
        }
        IGridNode node;
        try {
            node = host.getGridNode(side);
        } catch (Throwable t) {
            return null;
        }
        return node == null ? null : node.getGrid();
    }

    // ------------------------------------------------------------------
    // 网络侧的能力
    // ------------------------------------------------------------------

    @Nullable
    public static MEStorage getStorage(IGrid grid) {
        IStorageService service = grid.getStorageService();
        return service == null ? null : service.getInventory();
    }

    /**
     * 从网络里抽电。返回这次实际抽到的量。
     * <p>
     * 模式由调用方给：{@link Actionable#SIMULATE} 只是"问一问"，一点都不扣。
     * 想"先确认给得起、再真扣"的时候必须先用它——AE2 的 {@code extractAEPower} 是
     * 能抽多少抽多少，抽不满时调用方往往会放弃这次抽取，那半截就白扣了。
     */
    public static double extractNetworkPower(IGrid grid, double amount, Actionable mode) {
        IEnergyService energy = grid.getEnergyService();
        if (energy == null || amount <= 0) {
            return 0;
        }
        return energy.extractAEPower(amount, mode, PowerMultiplier.CONFIG);
    }
}
